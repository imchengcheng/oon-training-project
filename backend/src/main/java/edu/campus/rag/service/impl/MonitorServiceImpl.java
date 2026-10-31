package edu.campus.rag.service.impl;

import static edu.campus.rag.common.Api.*;
import static edu.campus.rag.common.TimeUtils.now;

import edu.campus.rag.entity.*;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.MonitorService;
import edu.campus.rag.service.support.ResponseAssembler;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MonitorServiceImpl implements MonitorService {
  private final DataMapper db;
  private final ResponseAssembler views;
  private final AppProperties settings;

  public MonitorServiceImpl(DataMapper db, ResponseAssembler views, AppProperties settings) {
    this.db = db;
    this.views = views;
    this.settings = settings;
  }

  public record Range(OffsetDateTime from, OffsetDateTime to) {
    boolean contains(OffsetDateTime date) {
      return !date.isBefore(from) && date.isBefore(to);
    }
  }

  public static Range range(String from, String to) {
    try {
      OffsetDateTime end = to == null ? now() : OffsetDateTime.parse(to),
          start = from == null ? end.minusDays(30) : OffsetDateTime.parse(from);
      require(
          start.isBefore(end) && Duration.between(start, end).compareTo(Duration.ofDays(90)) <= 0,
          400,
          "C-0001",
          "时间范围须大于 0 且不超过 90 天");
      return new Range(start, end);
    } catch (DateTimeException ex) {
      throw fail(400, "C-0001", "请使用带时区偏移的 ISO 8601 时间");
    }
  }

  private static double rate(long a, long b) {
    return b == 0 ? 0 : Math.round((double) a / b * 10000) / 10000.0;
  }

  public Map<String, Object> summary() {
    return db.tx(
        () -> {
          var all = db.list(Document.class, "from Document");
          var active = all.stream().filter(d -> "ACTIVE".equals(d.lifecycleStatus)).toList();
          var dist = new LinkedHashMap<String, Long>();
          for (String state : List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED"))
            dist.put(state, active.stream().filter(d -> state.equals(d.vectorStatus)).count());
          return map(
              "runtime",
              map("chatProvider", settings.modelMode,
                  "chatModel", "demo".equals(settings.modelMode) ? "本地演示" : settings.chatModel,
                  "embeddingMode", settings.effectiveEmbeddingMode(),
                  "embeddingModel", settings.embeddingModel,
                  "embeddingDimension", settings.embeddingDimension,
                  "retrievalMode", settings.retrievalMode,
                  "vectorStore", settings.vectorStore),
              "totalDocuments",
              active.size(),
              "totalChunks",
              active.stream()
                  .filter(ResponseAssembler::searchable)
                  .mapToInt(d -> d.chunkCount)
                  .sum(),
              "completedCount",
              dist.get("COMPLETED"),
              "completionRate",
              rate(dist.get("COMPLETED"), active.size()),
              "searchableDocumentCount",
              active.stream().filter(ResponseAssembler::searchable).count(),
              "deletingCount",
              all.stream().filter(d -> "DELETING".equals(d.lifecycleStatus)).count(),
              "pendingDeletionCount",
              all.stream()
                  .filter(d -> "DELETING".equals(d.lifecycleStatus) && d.cleanupCode == null)
                  .count(),
              "statusDistribution",
              dist);
        });
  }

  public Map<String, Object> quality(String from, String to) {
    var range = range(from, to);
    return db.tx(
        () -> {
          var users =
              db.list(
                  Message.class,
                  "from Message where role='USER' and createTime>=?1 and createTime<?2",
                  range.from,
                  range.to);
          long success = users.stream().filter(m -> "SUCCEEDED".equals(m.processingStatus)).count(),
              failed = users.stream().filter(m -> "FAILED".equals(m.processingStatus)).count();
          var counts = new HashMap<String, Long>();
          var cited = new HashMap<Long, List<Source>>();
          for (var u : users)
            if ("SUCCEEDED".equals(u.processingStatus)) {
              var a = db.one(Message.class, "from Message where replyToMessageId=?1", u.id);
              counts.merge(a.answerStatus, 1L, Long::sum);
              for (var source :
                  db.list(Source.class, "from Source where messageId=?1 order by id", a.id))
                cited.computeIfAbsent(source.docId, k -> new ArrayList<>()).add(source);
            }
          long answered = counts.getOrDefault("ANSWERED", 0L),
              noMatch = counts.getOrDefault("NO_MATCH", 0L),
              insufficient = counts.getOrDefault("INSUFFICIENT_EVIDENCE", 0L);
          var top =
              cited.entrySet().stream()
                  .sorted(
                      Comparator.<Map.Entry<Long, List<Source>>>comparingInt(
                              e -> e.getValue().size())
                          .reversed()
                          .thenComparing(Map.Entry.<Long, List<Source>>comparingByKey().reversed()))
                  .limit(10)
                  .map(
                      e -> {
                        var latest =
                            e.getValue().stream()
                                .max(Comparator.comparing(x -> x.id))
                                .orElseThrow();
                        return map(
                            "docId",
                            e.getKey().toString(),
                            "docTitle",
                            latest.docTitleSnapshot,
                            "citationCount",
                            e.getValue().size());
                      })
                  .toList();
          return map(
              "totalQuestions",
              users.size(),
              "succeededCount",
              success,
              "failedCount",
              failed,
              "processingCount",
              users.size() - success - failed,
              "answeredCount",
              answered,
              "noMatchCount",
              noMatch,
              "insufficientEvidenceCount",
              insufficient,
              "retrievalHitCount",
              answered + insufficient,
              "retrievalHitRate",
              rate(answered + insufficient, success),
              "answerRate",
              rate(answered, success),
              "failureRate",
              rate(failed, success + failed),
              "topSources",
              top);
        });
  }

  public Map<String, Object> feedback(int page, int size, String rating, String from, String to) {
    enumeration(rating, Set.of("LIKE", "DISLIKE"));
    var range = range(from, to);
    return db.tx(
        () ->
            page(
                db
                    .list(
                        Feedback.class,
                        "from Feedback where updateTime>=?1 and updateTime<?2 order by updateTime"
                            + " desc,id desc",
                        range.from,
                        range.to)
                    .stream()
                    .filter(f -> rating == null || rating.equals(f.rating))
                    .map(
                        f -> {
                          var a = db.get(Message.class, f.messageId);
                          var q = db.get(Message.class, a.replyToMessageId);
                          var r = views.feedback(f);
                          r.putAll(
                              map(
                                  "question",
                                  q.content,
                                  "answer",
                                  a.content,
                                  "answerStatus",
                                  a.answerStatus,
                                  "sources",
                                  views.sources(a.id, true)));
                          return r;
                        })
                    .toList(),
                page,
                size));
  }
}
