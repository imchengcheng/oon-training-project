package edu.campus.rag.service;

import static edu.campus.rag.common.Api.*;
import static edu.campus.rag.common.TimeUtils.now;

import edu.campus.rag.common.config.SecurityConfig;
import edu.campus.rag.entity.*;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.support.ResponseAssembler;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Source-backed campus services; published content never silently outlives its source generation. */
@Service
public class CampusService {
  public record GuideInput(String title, String category, String summary, String department,
      String materials, String steps, String processingTime, String contact,
      String sourceDocId, Boolean published) {}
  public record FaqInput(String question, String category, String answer, String sourceDocId,
      Boolean published, Integer sortOrder) {}
  public record TicketInput(String title, String category, String content, String relatedMessageId) {}
  public record TicketUpdate(String status, String reply) {}
  public record UserInput(String username, String realName, String studentNo, String password) {}
  public record UserUpdate(String realName, Boolean enabled) {}
  public record PasswordInput(String password) {}

  private final DataMapper db;
  private final PasswordEncoder passwords;

  public CampusService(DataMapper db, PasswordEncoder passwords) {
    this.db = db;
    this.passwords = passwords;
  }

  private <T> T transaction(Supplier<T> work) {
    try { return db.tx(work); }
    catch (DataIntegrityViolationException ex) {
      throw fail(409, "U-0002", "用户名或学号已存在，请检查后重试");
    }
  }

  private static Long actor(boolean administrator) {
    var identity = SecurityConfig.current();
    require((administrator ? "ADMIN" : "STUDENT").equals(identity.role()),
        403, "A-0003", "当前角色无权访问");
    return identity.id();
  }

  private static String required(String value, int max, String label) {
    String clean = optional(value, max, label);
    require(!clean.isEmpty(), 400, "C-0001", label + "不能为空");
    return clean;
  }

  private static String optional(String value, int max, String label) {
    String clean = value == null ? "" : value.strip();
    require(clean.codePointCount(0, clean.length()) <= max, 400, "C-0001", label + "超过长度限制");
    return clean;
  }

  private static String keyword(String value) {
    return optional(value, 200, "搜索词").toLowerCase(Locale.ROOT);
  }

  private static boolean matches(String keyword, String... values) {
    if (keyword.isEmpty()) return true;
    for (String value : values)
      if (value != null && value.toLowerCase(Locale.ROOT).contains(keyword)) return true;
    return false;
  }

  private static void paging(int page, int size) {
    require(page >= 1 && size >= 1 && size <= 100, 400, "C-0001", "分页参数无效");
  }

  private static String categoryFilter(String value) {
    return value == null || value.isBlank() ? null : category(value, "C-0001");
  }

  private static Long optionalId(String value) {
    return value == null || value.isBlank() ? null : id(value);
  }

  private boolean bodyAvailable(Document document) {
    return ResponseAssembler.searchable(document)
        && db.list(Chunk.class,
            "from Chunk where docId=?1 and generation=?2 and isDeleted=false order by chunkIndex",
            document.id, document.activeGeneration).stream()
            .anyMatch(c -> c.chunkText != null && !c.chunkText.isBlank());
  }

  private record SourceState(Document document, boolean available) {}

  private SourceState sourceState(Long sourceDocId, String sourceGeneration) {
    var document = sourceDocId == null ? null : db.get(Document.class, sourceDocId);
    boolean available = document != null && sourceGeneration != null
        && sourceGeneration.equals(document.activeGeneration) && bodyAvailable(document);
    return new SourceState(document, available);
  }

  private Document sourceForSave(String rawId, boolean published) {
    Long sourceId = optionalId(rawId);
    require(!published || sourceId != null, 400, "G-0001", "发布前须选择来源规章文档");
    if (sourceId == null) return null;
    var document = db.lock(Document.class, sourceId);
    require(document != null, 400, "G-0001", "来源文档不存在");
    require(!published || bodyAvailable(document), 409, "G-0001", "来源文档未激活或无有效正文，不能发布");
    return document;
  }

  private Map<String, Object> guideView(ServiceGuide g) {
    var source = sourceState(g.sourceDocId, g.sourceGeneration);
    return map("guideId", g.id.toString(), "title", g.title, "category", g.category,
        "summary", g.summary, "department", g.department, "materials", g.materials,
        "steps", g.steps, "processingTime", g.processingTime, "contact", g.contact,
        "sourceDocId", g.sourceDocId == null ? null : g.sourceDocId.toString(),
        "sourceTitle", source.document == null ? null : source.document.title,
        "sourceGeneration", g.sourceGeneration, "sourceAvailable", source.available,
        "published", g.published, "createdAt", g.createTime, "updatedAt", g.updateTime);
  }

  public Map<String, Object> guides(boolean administrator, int page, int size,
      String rawKeyword, String rawCategory, Boolean publishedFilter) {
    actor(administrator); paging(page, size);
    String search = keyword(rawKeyword), filter = categoryFilter(rawCategory);
    return transaction(() -> page(db.list(ServiceGuide.class,
        "from ServiceGuide order by updateTime desc,id desc").stream()
        .filter(g -> filter == null || filter.equals(g.category))
        .filter(g -> publishedFilter == null || publishedFilter == g.published)
        .filter(g -> matches(search, g.title, g.summary, g.department, g.materials, g.steps))
        .filter(g -> administrator || (g.published && sourceState(g.sourceDocId, g.sourceGeneration).available))
        .map(this::guideView).toList(), page, size));
  }

  public Map<String, Object> guide(Long guideId, boolean administrator) {
    actor(administrator);
    return transaction(() -> {
      var g = db.get(ServiceGuide.class, guideId);
      require(g != null && (administrator || (g.published && sourceState(g.sourceDocId, g.sourceGeneration).available)),
          404, "G-0002", "办事指南不存在或来源已更新，请查询当前规章");
      return guideView(g);
    });
  }

  public Map<String, Object> saveGuide(Long guideId, GuideInput body) {
    actor(true);
    require(body != null, 400, "C-0001", "请求内容为空");
    String title = required(body.title(), 140, "指南标题");
    String category = category(body.category(), "C-0001");
    String summary = optional(body.summary(), 500, "摘要");
    String department = optional(body.department(), 80, "受理部门");
    String materials = optional(body.materials(), 4000, "材料清单");
    String steps = optional(body.steps(), 10000, "办理步骤");
    String processingTime = optional(body.processingTime(), 200, "办理时间");
    String contact = optional(body.contact(), 500, "联系方式");
    boolean published = Boolean.TRUE.equals(body.published());
    require(!published || !steps.isEmpty(), 400, "G-0001", "发布指南前须填写办理步骤");
    return transaction(() -> {
      var g = guideId == null ? new ServiceGuide() : db.lock(ServiceGuide.class, guideId);
      require(g != null, 404, "G-0002", "办事指南不存在");
      var source = sourceForSave(body.sourceDocId(), published);
      g.title = title; g.category = category; g.summary = summary; g.department = department;
      g.materials = materials; g.steps = steps; g.processingTime = processingTime; g.contact = contact;
      g.sourceDocId = source == null ? null : source.id;
      g.sourceGeneration = source == null ? null : source.activeGeneration;
      g.published = published;
      if (guideId == null) db.save(g); else db.flush();
      return guideView(g);
    });
  }

  private Map<String, Object> faqView(FaqItem f) {
    var source = sourceState(f.sourceDocId, f.sourceGeneration);
    return map("faqId", f.id.toString(), "question", f.question, "category", f.category,
        "answer", f.answer, "sourceDocId", f.sourceDocId == null ? null : f.sourceDocId.toString(),
        "sourceTitle", source.document == null ? null : source.document.title,
        "sourceGeneration", f.sourceGeneration, "sourceAvailable", source.available,
        "published", f.published, "sortOrder", f.sortOrder, "useCount", f.useCount,
        "createdAt", f.createTime, "updatedAt", f.updateTime);
  }

  public Map<String, Object> faqs(boolean administrator, int page, int size,
      String rawKeyword, String rawCategory, Boolean publishedFilter) {
    actor(administrator); paging(page, size);
    String search = keyword(rawKeyword), filter = categoryFilter(rawCategory);
    return transaction(() -> page(db.list(FaqItem.class,
        "from FaqItem order by sortOrder,useCount desc,id desc").stream()
        .filter(f -> filter == null || filter.equals(f.category))
        .filter(f -> publishedFilter == null || publishedFilter == f.published)
        .filter(f -> matches(search, f.question, f.answer))
        .filter(f -> administrator || (f.published && sourceState(f.sourceDocId, f.sourceGeneration).available))
        .map(this::faqView).toList(), page, size));
  }

  public Map<String, Object> faq(Long faqId, boolean administrator) {
    actor(administrator);
    return transaction(() -> {
      var f = db.get(FaqItem.class, faqId);
      require(f != null && (administrator || (f.published && sourceState(f.sourceDocId, f.sourceGeneration).available)),
          404, "G-0002", "常见问题不存在或来源已更新，请查询当前规章");
      return faqView(f);
    });
  }

  public Map<String, Object> saveFaq(Long faqId, FaqInput body) {
    actor(true);
    require(body != null, 400, "C-0001", "请求内容为空");
    String question = required(body.question(), 300, "问题");
    String category = category(body.category(), "C-0001");
    String answer = optional(body.answer(), 10000, "参考解答");
    boolean published = Boolean.TRUE.equals(body.published());
    int order = body.sortOrder() == null ? 0 : body.sortOrder();
    require(order >= 0 && order <= 100000, 400, "C-0001", "排序须在0至100000之间");
    require(!published || !answer.isEmpty(), 400, "G-0001", "发布常见问题前须填写参考解答");
    return transaction(() -> {
      var f = faqId == null ? new FaqItem() : db.lock(FaqItem.class, faqId);
      require(f != null, 404, "G-0002", "常见问题不存在");
      var source = sourceForSave(body.sourceDocId(), published);
      f.question = question; f.category = category; f.answer = answer;
      f.sourceDocId = source == null ? null : source.id;
      f.sourceGeneration = source == null ? null : source.activeGeneration;
      f.published = published; f.sortOrder = order;
      if (faqId == null) db.save(f); else db.flush();
      return faqView(f);
    });
  }

  public Map<String, Object> useFaq(Long faqId) {
    actor(false);
    return transaction(() -> {
      var f = db.lock(FaqItem.class, faqId);
      require(f != null && f.published && sourceState(f.sourceDocId, f.sourceGeneration).available,
          404, "G-0002", "常见问题不存在或来源已更新");
      require(f.useCount < Long.MAX_VALUE, 409, "G-0003", "使用计数已达上限");
      f.useCount++; db.flush();
      return map("faqId", f.id.toString(), "question", f.question, "useCount", f.useCount);
    });
  }

  private Map<String, Object> ticketView(ConsultationTicket t) {
    var student = db.get(Account.class, t.studentId);
    var handler = t.handlerId == null ? null : db.get(Account.class, t.handlerId);
    return map("ticketId", t.id.toString(), "title", t.title, "category", t.category,
        "content", t.content, "relatedMessageId", t.relatedMessageId == null ? null : t.relatedMessageId.toString(),
        "status", t.status, "reply", t.reply, "studentId", t.studentId.toString(),
        "studentName", student == null ? null : student.realName,
        "studentNo", student == null ? null : student.studentNo,
        "handlerId", t.handlerId == null ? null : t.handlerId.toString(),
        "handlerName", handler == null ? null : handler.realName, "repliedAt", t.repliedAt,
        "createdAt", t.createTime, "updatedAt", t.updateTime);
  }

  public Map<String, Object> tickets(boolean administrator, int page, int size,
      String rawKeyword, String rawCategory, String rawStatus) {
    Long viewer = actor(administrator); paging(page, size);
    String search = keyword(rawKeyword), filter = categoryFilter(rawCategory);
    String status = rawStatus == null || rawStatus.isBlank() ? null : rawStatus;
    enumeration(status, Set.of("OPEN", "IN_PROGRESS", "RESOLVED"));
    return transaction(() -> page(db.list(ConsultationTicket.class,
        administrator ? "from ConsultationTicket order by updateTime desc,id desc"
            : "from ConsultationTicket where studentId=?1 order by updateTime desc,id desc",
        administrator ? new Object[0] : new Object[]{viewer}).stream()
        .filter(t -> filter == null || filter.equals(t.category))
        .filter(t -> status == null || status.equals(t.status))
        .filter(t -> matches(search, t.title, t.content, t.reply))
        .map(this::ticketView).toList(), page, size));
  }

  public Map<String, Object> ticket(Long ticketId, boolean administrator) {
    Long viewer = actor(administrator);
    return transaction(() -> {
      var t = db.get(ConsultationTicket.class, ticketId);
      require(t != null && (administrator || viewer.equals(t.studentId)), 404, "T-0001", "咨询工单不存在");
      return ticketView(t);
    });
  }

  public Map<String, Object> createTicket(TicketInput body) {
    Long viewer = actor(false);
    require(body != null, 400, "C-0001", "请求内容为空");
    String title = required(body.title(), 140, "咨询标题");
    String category = category(body.category(), "C-0001");
    String content = required(body.content(), 5000, "咨询内容");
    Long related = optionalId(body.relatedMessageId());
    return transaction(() -> {
      if (related != null) {
        var m = db.get(Message.class, related);
        var s = m == null ? null : db.get(Session.class, m.sessionId);
        require(s != null && s.userId.equals(viewer), 404, "T-0001", "关联问答不存在");
      }
      var t = new ConsultationTicket();
      t.studentId = viewer; t.title = title; t.category = category; t.content = content;
      t.relatedMessageId = related; t.status = "OPEN"; t.reply = "";
      db.save(t);
      return ticketView(t);
    });
  }

  public Map<String, Object> updateTicket(Long ticketId, TicketUpdate body) {
    Long viewer = actor(true);
    require(body != null && body.status() != null, 400, "C-0001", "工单状态不能为空");
    enumeration(body.status(), Set.of("OPEN", "IN_PROGRESS", "RESOLVED"));
    return transaction(() -> {
      var t = db.lock(ConsultationTicket.class, ticketId);
      require(t != null, 404, "T-0001", "咨询工单不存在");
      String reply = body.reply() == null ? optional(t.reply, 5000, "处理回复") : optional(body.reply(), 5000, "处理回复");
      require(!"RESOLVED".equals(body.status()) || !reply.isEmpty(), 400, "T-0002", "办结工单前须填写回复");
      t.status = body.status(); t.reply = reply; t.handlerId = viewer;
      t.repliedAt = reply.isEmpty() ? null : now(); db.flush();
      return ticketView(t);
    });
  }

  private Map<String, Object> userView(Account user) {
    return map("userId", user.id.toString(), "username", user.username, "realName", user.realName,
        "studentNo", user.studentNo, "roleType", user.roleType, "enabled", user.enabled,
        "createdAt", user.createTime, "updatedAt", user.updateTime);
  }

  public Map<String, Object> users(int page, int size, String rawKeyword, String rawRole) {
    actor(true); paging(page, size);
    String search = keyword(rawKeyword);
    String role = rawRole == null || rawRole.isBlank() ? null : rawRole;
    enumeration(role, Set.of("ADMIN", "STUDENT"));
    return transaction(() -> page(db.list(Account.class, "from Account order by createTime desc,id desc").stream()
        .filter(u -> role == null || role.equals(u.roleType))
        .filter(u -> matches(search, u.username, u.realName, u.studentNo))
        .map(this::userView).toList(), page, size));
  }

  private static String password(String value) {
    require(value != null && value.getBytes(StandardCharsets.UTF_8).length >= 10
            && value.getBytes(StandardCharsets.UTF_8).length <= 72
            && value.codePoints().anyMatch(Character::isLetter)
            && value.codePoints().anyMatch(Character::isDigit),
        400, "U-0001", "密码须含字母和数字，长度为10至72个UTF-8字节");
    return value;
  }

  public Map<String, Object> createUser(UserInput body) {
    Long viewer = actor(true);
    require(body != null, 400, "C-0001", "请求内容为空");
    String username = required(body.username(), 64, "用户名");
    require(username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}"),
        400, "U-0001", "用户名须为3至64位字母、数字、点、下划线或短横线，且以字母或数字开头");
    String name = required(body.realName(), 80, "姓名");
    String studentNo = required(body.studentNo(), 32, "学号");
    require(studentNo.matches("[A-Za-z0-9_-]+"), 400, "U-0001", "学号仅支持字母、数字、下划线和短横线");
    String hash = passwords.encode(password(body.password()));
    return transaction(() -> {
      db.lock(Account.class, viewer);
      require(db.one(Account.class, "from Account where username=?1 or studentNo=?2", username, studentNo) == null,
          409, "U-0002", "用户名或学号已存在");
      var u = new Account();
      u.username = username; u.realName = name; u.studentNo = studentNo;
      u.passwordHash = hash; u.roleType = "STUDENT"; u.enabled = true; db.save(u);
      return userView(u);
    });
  }

  public Map<String, Object> updateUser(Long userId, UserUpdate body) {
    Long viewer = actor(true);
    require(body != null && body.enabled() != null, 400, "C-0001", "账号状态不能为空");
    String name = required(body.realName(), 80, "姓名");
    require(!viewer.equals(userId) || body.enabled(), 409, "U-0003", "不能禁用当前登录账号");
    return transaction(() -> {
      var u = db.lock(Account.class, userId);
      require(u != null, 404, "U-0004", "用户不存在");
      u.realName = name; u.enabled = body.enabled(); db.flush();
      return userView(u);
    });
  }

  public Map<String, Object> resetPassword(Long userId, PasswordInput body) {
    Long viewer = actor(true);
    require(body != null, 400, "C-0001", "请求内容为空");
    require(!viewer.equals(userId), 403, "U-0003", "此入口仅用于重置学生密码");
    String hash = passwords.encode(password(body.password()));
    return transaction(() -> {
      var u = db.lock(Account.class, userId);
      require(u != null, 404, "U-0004", "用户不存在");
      require("STUDENT".equals(u.roleType), 403, "U-0003", "此入口仅用于重置学生密码");
      u.passwordHash = hash; db.flush();
      return map("userId", u.id.toString(), "reset", true);
    });
  }

  private static class Gap {
    String question, reason;
    long count;
    OffsetDateTime lastAskedAt;
    Gap(String question, String reason) { this.question = question; this.reason = reason; }
    void add(OffsetDateTime at) {
      count++;
      if (lastAskedAt == null || at.isAfter(lastAskedAt)) lastAskedAt = at;
    }
    Map<String, Object> view() {
      return map("question", question, "count", count, "lastAskedAt", lastAskedAt, "reason", reason);
    }
  }

  private Message succeededQuestion(Message answer) {
    if (answer == null || !"ASSISTANT".equals(answer.role) || answer.replyToMessageId == null) return null;
    var question = db.get(Message.class, answer.replyToMessageId);
    return question != null && "USER".equals(question.role)
        && "SUCCEEDED".equals(question.processingStatus) ? question : null;
  }

  private static void addGap(Map<String, Gap> gaps, Message question, String reason) {
    String text = question.content == null ? "" : question.content.strip().replaceAll("\\s+", " ");
    if (text.isEmpty()) return;
    String key = reason + "\u0000" + text.toLowerCase(Locale.ROOT);
    gaps.computeIfAbsent(key, k -> new Gap(text, reason)).add(question.createTime);
  }

  public Map<String, Object> gaps(String rawKeyword) {
    actor(true);
    String search = keyword(rawKeyword);
    return transaction(() -> {
      var gaps = new LinkedHashMap<String, Gap>();
      long noMatch = 0, insufficient = 0, dislike = 0;
      for (var answer : db.list(Message.class,
          "from Message where role='ASSISTANT' and answerStatus in ('NO_MATCH','INSUFFICIENT_EVIDENCE') order by id")) {
        var question = succeededQuestion(answer);
        if (question == null || !matches(search, question.content)) continue;
        if ("NO_MATCH".equals(answer.answerStatus)) noMatch++; else insufficient++;
        addGap(gaps, question, answer.answerStatus);
      }
      for (var feedback : db.list(Feedback.class, "from Feedback where rating='DISLIKE' order by id")) {
        var answer = db.get(Message.class, feedback.messageId);
        var question = succeededQuestion(answer);
        if (question == null || answer.answerStatus == null
            || !Set.of("ANSWERED", "NO_MATCH", "INSUFFICIENT_EVIDENCE").contains(answer.answerStatus)
            || !matches(search, question.content)) continue;
        dislike++; addGap(gaps, question, "DISLIKE");
      }
      long open = db.list(ConsultationTicket.class, "from ConsultationTicket where status<>'RESOLVED'")
          .stream().filter(t -> matches(search, t.title, t.content)).count();
      var items = gaps.values().stream()
          .sorted(Comparator.<Gap>comparingLong(g -> g.count).reversed()
              .thenComparing(g -> g.lastAskedAt, Comparator.reverseOrder())
              .thenComparing(g -> g.question).thenComparing(g -> g.reason))
          .limit(50).map(Gap::view).toList();
      return map("noMatchCount", noMatch, "insufficientCount", insufficient, "dislikeCount", dislike,
          "openTicketCount", open, "items", items,
          "scopeNote", "按成功完成问答的真实未命中、证据不足、负反馈聚合；排除技术失败。相同问题按原因分别计数，最多展示50项");
    });
  }
}
