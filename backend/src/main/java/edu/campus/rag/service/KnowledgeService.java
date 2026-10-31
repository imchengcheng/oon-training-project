package edu.campus.rag.service;

import static edu.campus.rag.common.Api.*;

import edu.campus.rag.entity.*;
import edu.campus.rag.entity.Document;
import edu.campus.rag.entity.Session;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.support.ResponseAssembler;
import java.util.*;
import org.springframework.stereotype.Service;

/** Student-owned reading, bookmarks and history. Reading never invokes a paid model. */
@Service
public class KnowledgeService {
  private final DataMapper db;

  public KnowledgeService(DataMapper db) { this.db = db; }

  public record FavoriteInput(String targetType, String targetId, String note) {}
  public record NoteInput(String note) {}
  public record TitleInput(String title) {}

  private static String keyword(String input) {
    String value = input == null ? "" : input.strip();
    require(value.codePointCount(0, value.length()) <= 100, 400, "C-0001", "搜索词不能超过100字");
    return value.toLowerCase(Locale.ROOT);
  }

  private static String note(String input) {
    String value = input == null ? "" : input.strip();
    require(value.codePointCount(0, value.length()) <= 500, 400, "C-0001", "收藏笔记不能超过500字");
    return value;
  }

  private static boolean contains(String value, String query) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(query);
  }

  private List<Chunk> chunks(Document d) {
    return db.list(Chunk.class,
        "from Chunk where docId=?1 and generation=?2 and isDeleted=false order by chunkIndex",
        d.id, d.activeGeneration);
  }

  private Document readable(Long id) {
    var doc = db.get(Document.class, id);
    require(doc != null && ResponseAssembler.searchable(doc), 404, "K-0001", "规章正文尚未发布或已停用");
    return doc;
  }

  private Session ownedSession(Long id, Long user, boolean lock) {
    var session = lock ? db.lock(Session.class, id) : db.get(Session.class, id);
    require(session != null && user.equals(session.userId), 404, "K-0002", "会话不存在");
    return session;
  }

  private Map<String, Object> documentView(Document d, Long user) {
    var favorite = db.one(KnowledgeBookmark.class,
        "from KnowledgeBookmark where userId=?1 and targetType='DOCUMENT' and targetId=?2", user, d.id);
    return map("docId", d.id.toString(), "title", d.title, "category", d.category,
        "fileType", d.fileType, "uploadTime", d.createTime, "updateTime", d.updateTime,
        "chunkCount", d.chunkCount, "favoriteId", favorite == null ? null : favorite.id.toString());
  }

  public Map<String, Object> documents(Long user, int pageNo, int size, String category, String rawKeyword) {
    if (category != null && !category.isBlank()) category(category, "C-0001");
    String search = keyword(rawKeyword);
    String selectedCategory = category;
    return db.tx(() -> {
      var docs = db.list(Document.class, "from Document where lifecycleStatus='ACTIVE' order by createTime desc,id desc");
      var result = new ArrayList<Map<String, Object>>();
      for (var d : docs) {
        if (!ResponseAssembler.searchable(d)
            || (selectedCategory != null && !selectedCategory.isBlank() && !selectedCategory.equals(d.category))) continue;
        String excerpt = "";
        if (!search.isEmpty() && !contains(d.title, search)) {
          var match = chunks(d).stream().filter(c -> contains(c.chunkText, search)).findFirst();
          if (match.isEmpty()) continue;
          excerpt = cut(match.get().chunkText, 180);
        }
        var view = documentView(d, user);
        view.put("excerpt", excerpt);
        result.add(view);
      }
      return page(result, pageNo, size);
    });
  }

  public Map<String, Object> document(Long id, Long user) {
    return db.tx(() -> {
      var d = readable(id);
      var result = documentView(d, user);
      result.put("sections", chunks(d).stream().map(c -> map(
          "chunkId", c.id.toString(), "sectionLabel", c.sectionLabel, "text", c.chunkText,
          "pageStart", c.pageStart, "pageEnd", c.pageEnd)).toList());
      return result;
    });
  }

  private Map<String, Object> favoriteView(KnowledgeBookmark f, Long user) {
    boolean available;
    String sessionId = null;
    String title = f.titleSnapshot;
    String preview = f.previewSnapshot;
    if ("DOCUMENT".equals(f.targetType)) {
      var d = db.get(Document.class, f.targetId);
      available = d != null && ResponseAssembler.searchable(d);
      if (available) {
        title = d.title;
        preview = chunks(d).stream().findFirst().map(c -> cut(c.chunkText, 500)).orElse("");
      }
    } else {
      var m = db.get(Message.class, f.targetId);
      var s = m == null ? null : db.get(Session.class, m.sessionId);
      available = m != null && s != null && user.equals(s.userId) && "ASSISTANT".equals(m.role);
      if (available) sessionId = s.id.toString();
    }
    return map("favoriteId", f.id.toString(), "targetType", f.targetType, "targetId", f.targetId.toString(),
        "title", title, "preview", preview, "note", f.note, "available", available,
        "sessionId", sessionId, "createTime", f.createTime, "updateTime", f.updateTime);
  }

  public Map<String, Object> favorites(Long user, int pageNo, int size, String targetType, String rawKeyword) {
    if (targetType != null && !targetType.isBlank()) enumeration(targetType, Set.of("DOCUMENT", "ANSWER"));
    String search = keyword(rawKeyword);
    return db.tx(() -> {
      var results = db.list(KnowledgeBookmark.class, "from KnowledgeBookmark where userId=?1 order by createTime desc,id desc", user)
          .stream().filter(f -> targetType == null || targetType.isBlank() || targetType.equals(f.targetType))
          .map(f -> favoriteView(f, user))
          .filter(f -> search.isEmpty() || contains((String) f.get("title"), search)
              || contains((String) f.get("preview"), search) || contains((String) f.get("note"), search)).toList();
      return page(results, pageNo, size);
    });
  }

  public Map<String, Object> favorite(Long user, FavoriteInput input) {
    require(input != null && Set.of("DOCUMENT", "ANSWER").contains(input.targetType() == null ? "" : input.targetType()),
        400, "C-0001", "收藏类型无效");
    Long target = id(input.targetId());
    String memo = note(input.note());
    return db.tx(() -> {
      // Serialize a user's idempotent favorite writes before checking the unique target.
      require(db.lock(Account.class, user) != null, 404, "K-0002", "用户不存在");
      String title;
      String preview;
      if ("DOCUMENT".equals(input.targetType())) {
        var doc = readable(target);
        title = doc.title;
        preview = chunks(doc).stream().findFirst().map(c -> cut(c.chunkText, 500)).orElse("");
      } else {
        var message = db.get(Message.class, target);
        require(message != null && "ASSISTANT".equals(message.role), 404, "K-0002", "回答不存在");
        ownedSession(message.sessionId, user, false);
        var question = message.replyToMessageId == null ? null : db.get(Message.class, message.replyToMessageId);
        title = cut(question == null || question.content == null ? "收藏的回答" : question.content, 180);
        preview = cut(message.content == null ? "" : message.content, 1800);
      }
      var favorite = db.one(KnowledgeBookmark.class,
          "from KnowledgeBookmark where userId=?1 and targetType=?2 and targetId=?3", user, input.targetType(), target);
      if (favorite == null) {
        favorite = new KnowledgeBookmark();
        favorite.userId = user; favorite.targetType = input.targetType(); favorite.targetId = target;
        favorite.titleSnapshot = title; favorite.previewSnapshot = preview; favorite.note = memo;
        db.save(favorite);
      } else if (input.note() != null) favorite.note = memo;
      return favoriteView(favorite, user);
    });
  }

  public Map<String, Object> lookupFavorite(Long user, String targetType, String targetId) {
    require(targetType != null && Set.of("DOCUMENT", "ANSWER").contains(targetType), 400, "C-0001", "收藏类型无效");
    Long target = id(targetId);
    return db.tx(() -> {
      var found = db.one(KnowledgeBookmark.class,
          "from KnowledgeBookmark where userId=?1 and targetType=?2 and targetId=?3", user, targetType, target);
      return map("favoriteId", found == null ? null : found.id.toString());
    });
  }

  private KnowledgeBookmark ownFavorite(Long id, Long user) {
    var f = db.lock(KnowledgeBookmark.class, id);
    require(f != null && user.equals(f.userId), 404, "K-0002", "收藏不存在");
    return f;
  }

  public Map<String, Object> updateFavorite(Long id, Long user, NoteInput input) {
    require(input != null && input.note() != null, 400, "C-0001", "请填写收藏笔记");
    String memo = note(input.note());
    return db.tx(() -> { var f = ownFavorite(id, user); f.note = memo; return favoriteView(f, user); });
  }

  public Map<String, Object> deleteFavorite(Long id, Long user) {
    return db.tx(() -> { db.remove(ownFavorite(id, user)); return map("favoriteId", id.toString(), "deleted", true); });
  }

  public Map<String, Object> sessions(Long user, int pageNo, int size, String status, String rawKeyword) {
    if (status != null && !status.isBlank()) enumeration(status, Set.of("ACTIVE", "CLOSED"));
    String search = keyword(rawKeyword);
    return db.tx(() -> {
      var results = new ArrayList<Map<String, Object>>();
      for (var s : db.list(Session.class, "from Session where userId=?1 order by lastActiveTime desc,id desc", user)) {
        if (status != null && !status.isBlank() && !status.equals(s.status)) continue;
        var messages = db.list(Message.class, "from Message where sessionId=?1 order by id", s.id);
        if (!search.isEmpty() && !contains(s.title, search)
            && messages.stream().noneMatch(m -> contains(m.content, search))) continue;
        results.add(map("sessionId", s.id.toString(), "title", s.title, "status", s.status,
            "createTime", s.createTime, "lastActiveTime", s.lastActiveTime, "messageCount", messages.size()));
      }
      return page(results, pageNo, size);
    });
  }

  public Map<String, Object> renameSession(Long id, Long user, TitleInput input) {
    String title = text(input == null ? null : input.title(), 100, "C-0001");
    return db.tx(() -> { var s = ownedSession(id, user, true); s.title = title; return map("sessionId", id.toString(), "title", s.title); });
  }

  public Map<String, Object> exportSession(Long id, Long user) {
    return db.tx(() -> {
      var s = ownedSession(id, user, false);
      var markdown = new StringBuilder("# ").append(s.title.replace('\n', ' ')).append("\n\n")
          .append("状态：").append(s.status).append("\n\n")
          .append("以下为本人的问答记录与回答时引用快照，不代表对制度现行性的确认。\n\n");
      for (var m : db.list(Message.class, "from Message where sessionId=?1 order by id", s.id)) {
        markdown.append("## ").append("USER".equals(m.role) ? "我的提问" : "系统回答")
            .append(" · ").append(m.createTime).append("\n\n").append(m.content == null ? "" : m.content).append("\n\n");
        if (m.processingStatus != null) markdown.append("处理状态：").append(m.processingStatus).append("\n\n");
        for (var source : db.list(Source.class, "from Source where messageId=?1 order by rankNo", m.id)) {
          markdown.append("### 来源 [").append(source.rankNo).append("] ").append(source.docTitleSnapshot).append("\n\n");
          if (source.sectionLabelSnapshot != null) markdown.append(source.sectionLabelSnapshot).append("\n\n");
          markdown.append(source.chunkTextSnapshot == null ? "" : source.chunkTextSnapshot).append("\n\n");
        }
      }
      return map("filename", "CampusRAG-会话-" + id + ".md", "markdown", markdown.toString());
    });
  }
}
