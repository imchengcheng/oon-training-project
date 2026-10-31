package edu.campus.rag.service.support;

import static edu.campus.rag.common.Api.map;

import edu.campus.rag.common.Api;
import edu.campus.rag.entity.*;
import edu.campus.rag.mapper.DataMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ResponseAssembler {
  private final DataMapper db;

  public ResponseAssembler(DataMapper db) {
    this.db = db;
  }

  public Map<String, Object> session(Session s) {
    return map(
        "sessionId",
        s.id.toString(),
        "title",
        s.title,
        "status",
        s.status,
        "createTime",
        s.createTime,
        "lastActiveTime",
        s.lastActiveTime);
  }

  public Map<String, Object> feedback(Feedback f) {
    return f == null
        ? null
        : map(
            "feedbackId",
            f.id.toString(),
            "messageId",
            f.messageId.toString(),
            "rating",
            f.rating,
            "remark",
            f.remark,
            "createTime",
            f.createTime,
            "updateTime",
            f.updateTime);
  }

  public Map<String, Object> document(Document d, boolean detail) {
    var r =
        map(
            "docId",
            d.id.toString(),
            "title",
            d.title,
            "category",
            d.category,
            "fileType",
            d.fileType,
            "lifecycleStatus",
            d.lifecycleStatus,
            "vectorStatus",
            d.vectorStatus,
            "searchable",
            searchable(d),
            "chunkCount",
            d.chunkCount,
            "uploaderId",
            d.uploaderId.toString(),
            "uploadTime",
            d.createTime,
            "updateTime",
            d.updateTime,
            "lastError",
            error(d.errorCode, d.errorStage, d.errorMessage),
            "cleanupError",
            error(d.cleanupCode, d.cleanupStage, d.cleanupMessage));
    if (detail)
      r.putAll(
          map(
              "originalFilename",
              d.originalFilename,
              "fileSize",
              d.fileSize,
              "activeGeneration",
              d.activeGeneration,
              "currentTask",
              map(
                  "taskId",
                  d.taskId,
                  "status",
                  d.vectorStatus,
                  "stage",
                  d.taskStage,
                  "startedTime",
                  d.taskStartedTime,
                  "finishedTime",
                  d.taskFinishedTime)));
    return r;
  }

  public static boolean searchable(Document d) {
    return d != null && "ACTIVE".equals(d.lifecycleStatus) && d.activeGeneration != null;
  }

  public static Map<String, Object> error(String code, String stage, String message) {
    return code == null ? null : map("code", code, "stage", stage, "message", message);
  }

  public Map<String, Object> source(Source s, boolean full) {
    var d = db.get(Document.class, s.docId);
    String status =
        d == null
            ? "UNAVAILABLE"
            : !"ACTIVE".equals(d.lifecycleStatus)
                ? "DELETED"
                : d.activeGeneration == null
                    ? "UNAVAILABLE"
                    : !d.activeGeneration.equals(s.generation) ? "SUPERSEDED" : "ACTIVE";
    var result =
        map(
            "sourceId",
            s.id.toString(),
            "rankNo",
            s.rankNo,
            "chunkId",
            s.chunkId.toString(),
            "docId",
            s.docId.toString(),
            "generation",
            s.generation,
            "docTitle",
            s.docTitleSnapshot,
            "category",
            s.categorySnapshot,
            "sectionLabel",
            s.sectionLabelSnapshot,
            "pageStart",
            s.pageStart,
            "pageEnd",
            s.pageEnd,
            "similarityScore",
            s.similarityScore,
            "snippet",
            Api.cut(s.chunkTextSnapshot, 300),
            "sourceStatus",
            status);
    if (full) result.put("fullText", s.chunkTextSnapshot);
    return result;
  }

  public List<Map<String, Object>> sources(Long id, boolean full) {
    return db.list(Source.class, "from Source where messageId=?1 order by rankNo", id).stream()
        .map(s -> source(s, full))
        .toList();
  }

  public Map<String, Object> message(Message m, boolean expanded) {
    var r =
        map(
            "messageId",
            m.id.toString(),
            "sessionId",
            m.sessionId.toString(),
            "role",
            m.role,
            "content",
            m.content,
            "createTime",
            m.createTime);
    if ("USER".equals(m.role))
      r.putAll(
          map(
              "processingStatus",
              m.processingStatus,
              "clientRequestId",
              m.clientRequestId,
              "rewrittenQuery",
              m.rewrittenQuery,
              "errorCode",
              m.errorCode,
              "attemptCount",
              m.attemptCount));
    else {
      r.put("replyToMessageId", m.replyToMessageId.toString());
      if (expanded)
        r.putAll(
            map(
                "answerStatus",
                m.answerStatus,
                "sources",
                sources(m.id, false),
                "feedback",
                feedback(db.one(Feedback.class, "from Feedback where messageId=?1", m.id))));
    }
    return r;
  }

  public Map<String, Object> answer(Message user) {
    var a = db.one(Message.class, "from Message where replyToMessageId=?1", user.id);
    return map(
        "sessionId",
        user.sessionId.toString(),
        "clientRequestId",
        user.clientRequestId,
        "rewrittenQuery",
        user.rewrittenQuery,
        "matched",
        !"NO_MATCH".equals(a.answerStatus),
        "answerStatus",
        a.answerStatus,
        "userMessage",
        message(user, false),
        "assistantMessage",
        message(a, false),
        "sources",
        sources(a.id, false));
  }
}
