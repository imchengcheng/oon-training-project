package edu.campus.rag.service.impl;

import static edu.campus.rag.common.Api.*;
import static edu.campus.rag.common.TimeUtils.now;

import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.common.exception.BusinessException;
import edu.campus.rag.entity.*;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.QuestionService;
import edu.campus.rag.service.support.ModelGateway;
import edu.campus.rag.service.support.ResponseAssembler;
import edu.campus.rag.service.support.VectorStore;
import edu.campus.rag.service.support.HybridRetriever;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class QuestionServiceImpl implements QuestionService {
  private final DataMapper db;
  private final ResponseAssembler views;
  private final ModelGateway model;
  private final VectorStore vectors;
  private final HybridRetriever hybrid;
  private final AppProperties settings;
  private final ExecutorService workers =
      new ThreadPoolExecutor(
          5,
          5,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(25),
          r -> {
            var t = new Thread(r, "question-worker");
            t.setDaemon(true);
            return t;
          });

  private record Accepted(Message message, boolean replay) {}

  private record Computed(
      String rewritten,
      String status,
      String text,
      List<ModelGateway.Evidence> sources,
      int retrieved) {}

  public QuestionServiceImpl(
      DataMapper db,
      ResponseAssembler views,
      ModelGateway model,
      VectorStore vectors,
      HybridRetriever hybrid,
      AppProperties s) {
    this.db = db;
    this.views = views;
    this.model = model;
    this.vectors = vectors;
    this.hybrid = hybrid;
    this.settings = s;
  }

  @PreDestroy
  void stop() {
    workers.shutdownNow();
  }

  Session owned(Long id, Long user, boolean lock) {
    var s = lock ? db.lock(Session.class, id) : db.get(Session.class, id);
    require(s != null && s.userId.equals(user), 404, "Q-0002", "会话不存在");
    return s;
  }

  public Map<String, Object> create(Long user, Map<String, Object> body) {
    require(body == null || body.isEmpty(), 400, "C-0001", "创建会话不接受归属等参数");
    return db.tx(
        () -> {
          var s = new Session();
          s.userId = user;
          s.lastActiveTime = now();
          db.save(s);
          return views.session(s);
        });
  }

  public Map<String, Object> sessions(Long user, int page, int size, String status) {
    enumeration(status, Set.of("ACTIVE", "CLOSED"));
    return db.tx(
        () ->
            page(
                db
                    .list(
                        Session.class,
                        "from Session where userId=?1 order by lastActiveTime desc,id desc",
                        user)
                    .stream()
                    .filter(s -> status == null || status.equals(s.status))
                    .map(views::session)
                    .toList(),
                page,
                size));
  }

  public Map<String, Object> detail(Long id, Long user, int page, int size) {
    return db.tx(
        () ->
            map(
                "session",
                views.session(owned(id, user, false)),
                "messages",
                page(
                    db
                        .list(
                            Message.class,
                            "from Message where sessionId=?1 order by createTime,id",
                            id)
                        .stream()
                        .map(m -> views.message(m, true))
                        .toList(),
                    page,
                    size)));
  }

  public Map<String, Object> close(Long id, Long user) {
    return db.tx(
        () -> {
          var s = owned(id, user, true);
          require(s.processingMessageId == null, 409, "Q-0007", "会话存在处理中问题");
          if ("ACTIVE".equals(s.status)) {
            s.status = "CLOSED";
            s.lastActiveTime = now();
          }
          return map("sessionId", s.id.toString(), "status", s.status);
        });
  }

  public Map<String, Object> ask(Long sessionId, Long user, String requestId, String rawContent) {
    // Ownership precedes input checks to keep resource existence private.
    db.tx(() -> owned(sessionId, user, false));
    String content = text(rawContent, 1000, "Q-0001");
    try {
      require(
          requestId != null && UUID.fromString(requestId).toString().equalsIgnoreCase(requestId),
          400,
          "Q-0001",
          "clientRequestId 必须为 UUID");
    } catch (IllegalArgumentException ex) {
      throw fail(400, "Q-0001", "clientRequestId 必须为 UUID");
    }
    Accepted accepted =
        db.tx(
            () -> {
              var s = owned(sessionId, user, true);
              var previous =
                  db.one(
                      Message.class,
                      "from Message where sessionId=?1 and clientRequestId=?2",
                      sessionId,
                      requestId);
              if (previous != null) {
                require(content.equals(previous.content), 409, "Q-0008", "幂等标识对应的问题内容不同");
                if ("SUCCEEDED".equals(previous.processingStatus))
                  return new Accepted(previous, true);
                require(!"PROCESSING".equals(previous.processingStatus), 409, "Q-0007", "该问题仍在处理中");
              }
              require("ACTIVE".equals(s.status), 409, "Q-0003", "会话已关闭");
              require(s.processingMessageId == null, 409, "Q-0007", "会话存在处理中问题");
              if (previous != null) {
                var latest =
                    db.one(
                        Message.class,
                        "from Message where sessionId=?1 and role='USER' order by id desc",
                        sessionId);
                require(latest.id.equals(previous.id), 409, "Q-0008", "失败问题已有后续问题");
              }
              var m = previous == null ? new Message() : previous;
              m.sessionId = sessionId;
              m.role = "USER";
              m.content = content;
              m.clientRequestId = requestId;
              m.processingStatus = "PROCESSING";
              m.errorCode = null;
              m.rewrittenQuery = null;
              m.attemptCount++;
              m.lastAttemptTime = now();
              if (previous == null) db.save(m);
              if ("新会话".equals(s.title)) s.title = cut(content, 30);
              s.processingMessageId = m.id;
              s.lastActiveTime = now();
              return new Accepted(m, false);
            });
    var message = accepted.message;
    if (accepted.replay) return db.tx(() -> views.answer(db.get(Message.class, message.id)));
    long start = System.nanoTime();
    Future<Computed> future = null;
    try {
      future = workers.submit(() -> compute(message));
      var result = future.get(settings.questionTimeoutSeconds, TimeUnit.SECONDS);
      return db.tx(
          () -> {
            var session = owned(sessionId, user, true);
            var u = db.get(Message.class, message.id);
            require(
                "PROCESSING".equals(u.processingStatus)
                    && message.id.equals(session.processingMessageId),
                409,
                "Q-0007",
                "请求已结束");
            require(
                System.nanoTime() - start
                    < TimeUnit.SECONDS.toNanos(settings.questionTimeoutSeconds),
                504,
                "Q-0009",
                "问答处理超时");
            // Lock all referenced documents in ID order. Activation/deletion uses the same locks.
            Map<Long, Document> documents = new HashMap<>();
            result.sources.stream()
                .map(e -> e.chunk().docId)
                .distinct()
                .sorted()
                .forEach(id -> documents.put(id, db.lock(Document.class, id)));
            for (var e : result.sources) {
              var d = documents.get(e.chunk().docId);
              require(
                  ResponseAssembler.searchable(d)
                      && d.activeGeneration.equals(e.chunk().generation),
                  409,
                  "Q-0012",
                  "回答生成期间来源已更新，请重试");
            }
            var a = new Message();
            a.sessionId = sessionId;
            a.role = "ASSISTANT";
            a.content = result.text;
            a.replyToMessageId = u.id;
            a.answerStatus = result.status;
            db.save(a);
            for (int i = 0; i < result.sources.size(); i++) {
              var e = result.sources.get(i);
              var c = e.chunk();
              var d = documents.get(c.docId);
              var source = new Source();
              source.messageId = a.id;
              source.chunkId = c.id;
              source.docId = c.docId;
              source.generation = c.generation;
              source.similarityScore = e.score();
              source.rankNo = i + 1;
              source.docTitleSnapshot = d.title;
              source.categorySnapshot = d.category;
              source.sectionLabelSnapshot = c.sectionLabel;
              source.pageStart = c.pageStart;
              source.pageEnd = c.pageEnd;
              source.chunkTextSnapshot = c.chunkText;
              db.save(source);
            }
            u.processingStatus = "SUCCEEDED";
            u.rewrittenQuery = result.rewritten;
            u.retrievedCount = result.retrieved;
            u.durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            session.processingMessageId = null;
            session.lastActiveTime = now();
            db.flush();
            return views.answer(u);
          });
    } catch (Exception ex) {
      if (future != null) future.cancel(true);
      Throwable cause = ex instanceof ExecutionException ? ex.getCause() : ex;
      BusinessException failure =
          cause instanceof BusinessException f
              ? f
              : ex instanceof TimeoutException
                  ? fail(504, "Q-0009", "问答处理超时")
                  : fail(500, "C-0003", "问答处理或保存失败");
      try {
        failMessage(
            message.id, failure.code, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      } catch (Exception ignored) {
        LoggerFactory.getLogger(getClass())
            .error("clientRequestId={} failureStateWriteFailed", requestId);
      }
      failure.data =
          map(
              "clientRequestId",
              requestId,
              "userMessageId",
              message.id.toString(),
              "retryable",
              true);
      throw failure;
    } finally {
      LoggerFactory.getLogger(getClass())
          .info(
              "clientRequestId={} durationMs={} modelMode={} model={}",
              requestId,
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
              settings.modelMode,
              settings.chatModel);
    }
  }

  private Computed compute(Message message) {
    List<String> history =
        db.tx(
            () -> {
              var successful =
                  db.list(
                      Message.class,
                      "from Message where sessionId=?1 and role='USER' and"
                          + " processingStatus='SUCCEEDED' order by id desc",
                      message.sessionId);
              var turns = new ArrayList<String>();
              int bytes = 0;
              for (var u : successful.stream().limit(10).toList()) {
                var a = db.one(Message.class, "from Message where replyToMessageId=?1", u.id);
                if (a == null) continue;
                String turn = "学生：" + u.content + "\n助手：" + a.content;
                int n = turn.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + n > 4000) break;
                turns.add(turn);
                bytes += n;
              }
              Collections.reverse(turns);
              return turns;
            });
    String rewritten = model.rewrite(message.content, history);
    db.tx(
        () -> {
          var u = db.get(Message.class, message.id);
          if ("PROCESSING".equals(u.processingStatus)) u.rewrittenQuery = rewritten;
          return null;
        });
    checkInterrupted();
    float[] embedding = model.embed(rewritten);
    checkInterrupted();
    int limit = settings.topK * 4;
    var evidence = valid(vectors.search(embedding, limit));
    if (evidence.size() < settings.topK) {
      int all = db.tx(() -> db.list(Chunk.class, "from Chunk").size());
      if (all > limit) evidence = valid(vectors.search(embedding, Math.max(limit, all)));
    }
    evidence = hybrid.merge(rewritten, embedding, evidence);
    if (evidence.isEmpty()) return new Computed(rewritten, "NO_MATCH", "未找到相关规章条款", List.of(), 0);
    var selected = new ArrayList<ModelGateway.Evidence>();
    int usedBytes = 0;
    for (var e : evidence) {
      int bytes = e.chunk().chunkText.getBytes(StandardCharsets.UTF_8).length + 60;
      if (usedBytes + bytes <= 4000) {
        selected.add(e);
        usedBytes += bytes;
      }
      if (selected.size() >= settings.topK) break;
    }
    require(!selected.isEmpty(), 502, "Q-0010", "证据超过上下文预算");
    var draft = model.generate(rewritten, selected);
    checkInterrupted();
    if (!draft.sufficient())
      return new Computed(
          rewritten, "INSUFFICIENT_EVIDENCE", "现有规章条款不足以回答该问题", List.of(), selected.size());
    require(
        draft.answer() != null && !draft.answer().isBlank() && draft.answer().length() <= 16000,
        502,
        "Q-0010",
        "回答内容无效");
    var matcher = Pattern.compile("\\[([0-9]+)\\]").matcher(draft.answer());
    var order = new LinkedHashMap<Integer, Integer>();
    var text = new StringBuffer();
    while (matcher.find()) {
      int n;
      try {
        n = Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException ex) {
        throw fail(502, "Q-0010", "引用编号无效");
      }
      require(n >= 1 && n <= selected.size(), 502, "Q-0010", "模型引用了未提供的来源");
      int rank = order.computeIfAbsent(n, k -> order.size() + 1);
      matcher.appendReplacement(text, Matcher.quoteReplacement("[" + rank + "]"));
    }
    matcher.appendTail(text);
    require(!order.isEmpty(), 502, "Q-0010", "回答缺少有效引用");
    return new Computed(
        rewritten,
        "ANSWERED",
        text.toString(),
        order.keySet().stream().map(i -> selected.get(i - 1)).toList(),
        selected.size());
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) throw fail(504, "Q-0009", "问答处理超时");
  }

  private List<ModelGateway.Evidence> valid(List<VectorStore.Hit> hits) {
    return db.tx(
        () ->
            hits.stream()
                .filter(h -> h.score() >= settings.similarityThreshold)
                .map(
                    h -> {
                      var c = db.get(Chunk.class, h.chunkId());
                      if (c == null
                          || c.isDeleted
                          || !settings.embeddingModel.equals(c.embeddingModel)
                          || settings.embeddingDimension != c.embeddingDimension) return null;
                      var d = db.get(Document.class, c.docId);
                      return ResponseAssembler.searchable(d)
                              && d.activeGeneration.equals(c.generation)
                          ? new ModelGateway.Evidence(c, h.score())
                          : null;
                    })
                .filter(Objects::nonNull)
                .toList());
  }

  public void failMessage(Long id, String code, long duration) {
    db.tx(
        () -> {
          var u = db.get(Message.class, id);
          var s = db.lock(Session.class, u.sessionId);
          if ("PROCESSING".equals(u.processingStatus)) {
            u.processingStatus = "FAILED";
            u.errorCode = code;
            u.durationMs = duration;
          }
          if (id.equals(s.processingMessageId)) s.processingMessageId = null;
          return null;
        });
    LoggerFactory.getLogger(getClass())
        .warn("userMessageId={} durationMs={} errorCode={}", id, duration, code);
  }

  @Scheduled(fixedDelay = 10000)
  void expire() {
    var pending =
        db.tx(
            () ->
                db.list(
                    Message.class,
                    "from Message where role='USER' and processingStatus='PROCESSING'"));
    for (var m : pending)
      if (m.lastAttemptTime.plusSeconds(settings.questionTimeoutSeconds + 10).isBefore(now()))
        failMessage(m.id, "Q-0009", settings.questionTimeoutSeconds * 1000L);
  }

  Message ownedMessage(Long messageId, Long user, String error) {
    var m = db.get(Message.class, messageId);
    var session = m == null ? null : db.get(Session.class, m.sessionId);
    require(session != null && session.userId.equals(user), 404, error, "消息或来源不存在");
    return m;
  }

  public Map<String, Object> source(Long messageId, Long sourceId, Long user) {
    return db.tx(
        () -> {
          var m = ownedMessage(messageId, user, "Q-0011");
          var source = db.get(Source.class, sourceId);
          require(
              "ASSISTANT".equals(m.role) && source != null && source.messageId.equals(messageId),
              404,
              "Q-0011",
              "来源不存在");
          return views.source(source, true);
        });
  }

  public Map<String, Object> feedback(Long messageId, Long user, String rating, String remark) {
    return db.tx(
        () -> {
          var m = ownedMessage(messageId, user, "F-0001");
          db.lock(Session.class, m.sessionId);
          require("ASSISTANT".equals(m.role), 400, "F-0002", "仅可评价助手消息");
          require(
              Set.of("LIKE", "DISLIKE").contains(rating == null ? "" : rating),
              400,
              "F-0002",
              "评价值无效");
          String clean = remark == null ? null : remark.strip();
          require(
              clean == null || clean.codePointCount(0, clean.length()) <= 500,
              400,
              "F-0002",
              "备注超过 500 字符");
          var f =
              db.one(
                  Feedback.class,
                  "from Feedback where messageId=?1 and userId=?2",
                  messageId,
                  user);
          boolean fresh = f == null;
          if (fresh) f = new Feedback();
          f.messageId = messageId;
          f.userId = user;
          f.rating = rating;
          f.remark = clean;
          if (fresh) db.save(f);
          else db.flush();
          return views.feedback(f);
        });
  }
}
