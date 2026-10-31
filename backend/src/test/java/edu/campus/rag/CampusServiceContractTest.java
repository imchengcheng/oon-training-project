package edu.campus.rag;

import static edu.campus.rag.common.Api.map;
import static edu.campus.rag.common.TimeUtils.now;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.entity.*;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.support.VectorStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = MysqlTestDatabaseGuard.class)
class CampusServiceContractTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired DataMapper db;
  @Autowired VectorStore vectors;
  @Autowired AppProperties settings;
  String admin, student, other;
  Long adminId, studentId;

  @BeforeEach
  void setup() throws Exception {
    settings.loginLimit = 10000;
    for (var d : db.tx(() -> db.list(Document.class, "from Document"))) vectors.delete(d.id, null);
    db.tx(() -> {
      for (String entity : List.of("ConsultationTicket", "FaqItem", "ServiceGuide", "Feedback", "Source", "Message", "Session", "Chunk", "Document"))
        db.executeUpdate("delete from " + entity);
      for (var u : db.list(Account.class, "from Account where username in ('admin','stu2023001','stu2023002')")) u.enabled = true;
      db.executeUpdate("delete from Account where username like 'campus_test_%'");
      return null;
    });
    admin = login("admin", "Admin@123456", 200).at("/data/token").asText();
    student = login("stu2023001", "Student@123456", 200).at("/data/token").asText();
    other = login("stu2023002", "Student@123456", 200).at("/data/token").asText();
    adminId = account("admin"); studentId = account("stu2023001");
  }

  @AfterEach
  void restoreSeedAccounts() {
    db.tx(() -> {
      for (var u : db.list(Account.class, "from Account where username in ('admin','stu2023001','stu2023002')")) u.enabled = true;
      return null;
    });
  }

  private Long account(String username) {
    return db.tx(() -> db.one(Account.class, "from Account where username=?1", username).id);
  }

  private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) throws Exception {
    return request.contentType("application/json").content(json.writeValueAsBytes(body));
  }

  private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }

  private JsonNode run(MockHttpServletRequestBuilder request, int expected) throws Exception {
    return json.readTree(mvc.perform(request).andExpect(status().is(expected))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private JsonNode login(String username, String password, int expected) throws Exception {
    return run(body(post("/api/auth/login"), map("username", username, "password", password)), expected);
  }

  private Long source(boolean hasBody) {
    return db.tx(() -> {
      var d = new Document();
      d.title = "测试来源规章"; d.category = "STUDENT_STATUS"; d.filePath = "./target/test-files/campus-source.txt";
      d.uploaderId = adminId; d.fileType = "TXT"; d.lifecycleStatus = "ACTIVE";
      d.vectorStatus = "COMPLETED"; d.activeGeneration = UUID.randomUUID().toString(); d.chunkCount = hasBody ? 1 : 0;
      db.save(d);
      if (hasBody) addChunk(d);
      return d.id;
    });
  }

  private void addChunk(Document document) {
    var c = new Chunk();
    c.docId = document.id; c.generation = document.activeGeneration; c.chunkIndex = 0;
    c.chunkText = "第一条 本测试条款用于验证来源约束。";
    c.embeddingModel = settings.embeddingModel; c.embeddingDimension = settings.embeddingDimension;
    db.save(c);
  }

  private Map<String, Object> guideBody(Long sourceId, boolean published) {
    return map("title", "测试办事指南", "category", "STUDENT_STATUS", "summary", "人工维护的办事摘要",
        "department", "测试受理部门", "materials", "请核对来源中的材料要求", "steps", "阅读并核对来源规章正文。",
        "processingTime", "以主管部门通知为准", "contact", "请查阅来源",
        "sourceDocId", sourceId == null ? null : sourceId.toString(), "published", published);
  }

  @Test
  void publishedGuidesAndFaqsRequireRealSourceAndBecomeHiddenAfterSourceChanges() throws Exception {
    run(body(auth(post("/api/admin/campus/guides"), admin), guideBody(null, true)), 400);
    Long empty = source(false);
    run(body(auth(post("/api/admin/campus/guides"), admin), guideBody(empty, true)), 409);
    var draft = run(body(auth(post("/api/admin/campus/guides"), admin), guideBody(null, false)), 201).get("data");
    run(auth(get("/api/student/campus/guides/" + draft.get("guideId").asText()), student), 404);
    Long source = source(true);
    var guide = run(body(auth(post("/api/admin/campus/guides"), admin), guideBody(source, true)), 201).get("data");
    String id = guide.get("guideId").asText();
    assertTrue(guide.get("sourceAvailable").asBoolean());
    assertEquals("测试来源规章", guide.get("sourceTitle").asText());
    assertEquals(1, run(auth(get("/api/student/campus/guides"), student), 200).at("/data/total").asInt());
    run(auth(get("/api/student/campus/guides/" + id), student), 200);
    var faq = run(body(auth(post("/api/admin/campus/faqs"), admin), map(
        "question", "来源在哪里？", "category", "STUDENT_STATUS", "answer", "请阅读关联规章。",
        "sourceDocId", source.toString(), "published", true, "sortOrder", 10)), 201).get("data");
    String faqId = faq.get("faqId").asText();
    run(auth(post("/api/student/campus/faqs/" + faqId + "/use"), student), 200);
    assertEquals(2, run(auth(post("/api/student/campus/faqs/" + faqId + "/use"), other), 200).at("/data/useCount").asInt());
    db.tx(() -> {
      var d = db.lock(Document.class, source); d.activeGeneration = UUID.randomUUID().toString(); addChunk(d); return null;
    });
    run(auth(get("/api/student/campus/guides/" + id), student), 404);
    run(auth(post("/api/student/campus/faqs/" + faqId + "/use"), student), 404);
    assertFalse(run(auth(get("/api/admin/campus/guides/" + id), admin), 200).at("/data/sourceAvailable").asBoolean());
    run(body(auth(put("/api/admin/campus/guides/" + id), admin), guideBody(source, true)), 200);
    run(auth(get("/api/student/campus/guides/" + id), student), 200);
    db.tx(() -> { db.lock(Document.class, source).lifecycleStatus = "DELETED"; return null; });
    assertEquals(0, run(auth(get("/api/student/campus/guides"), student), 200).at("/data/total").asInt());
    run(auth(get("/api/student/campus/guides?size=101"), student), 400);
    run(auth(get("/api/admin/campus/guides"), student), 403);
    run(auth(get("/api/student/campus/guides"), admin), 403);
  }

  private Long question(Long owner, String content, String processing) {
    var s = new Session(); s.userId = owner; s.lastActiveTime = now(); db.save(s);
    var m = new Message(); m.sessionId = s.id; m.role = "USER"; m.content = content;
    m.processingStatus = processing; m.clientRequestId = UUID.randomUUID().toString(); db.save(m);
    return m.id;
  }

  @Test
  void ticketsEnforceOwnershipAndRequireReplyBeforeResolution() throws Exception {
    Long message = db.tx(() -> question(studentId, "我的规章问题", "SUCCEEDED"));
    var payload = map("title", "需要人工核验", "category", "ACADEMIC_AFFAIRS", "content", "请确认具体办理要求。", "relatedMessageId", message.toString());
    run(body(auth(post("/api/student/campus/tickets"), other), payload), 404);
    var ticket = run(body(auth(post("/api/student/campus/tickets"), student), payload), 201).get("data");
    String id = ticket.get("ticketId").asText();
    assertEquals(studentId.toString(), ticket.get("studentId").asText());
    run(auth(get("/api/student/campus/tickets/" + id), other), 404);
    assertEquals(0, run(auth(get("/api/student/campus/tickets"), other), 200).at("/data/total").asInt());
    run(body(auth(put("/api/admin/campus/tickets/" + id), admin), map("status", "RESOLVED", "reply", "")), 400);
    run(body(auth(put("/api/admin/campus/tickets/" + id), student), map("status", "RESOLVED", "reply", "伪造回复")), 403);
    var processed = run(body(auth(put("/api/admin/campus/tickets/" + id), admin),
        map("status", "IN_PROGRESS", "reply", "正在核验来源。")), 200).get("data");
    assertEquals(adminId.toString(), processed.get("handlerId").asText());
    assertFalse(processed.get("repliedAt").isNull());
    run(body(auth(put("/api/admin/campus/tickets/" + id), admin), map("status", "RESOLVED", "reply", "已核验，请以来源条款为准。")), 200);
    assertEquals("RESOLVED", run(auth(get("/api/student/campus/tickets/" + id), student), 200).at("/data/status").asText());
    run(body(auth(post("/api/student/campus/tickets"), admin), payload), 403);
    payload.put("studentId", "1");
    run(body(auth(post("/api/student/campus/tickets"), student), payload), 400);
  }

  @Test
  void userManagementCreatesStudentsWithoutLeakingHashesAndDisablingRevokesAccess() throws Exception {
    String username = "campus_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    var payload = map("username", username, "realName", "测试学生", "studentNo", "CT" + System.nanoTime(), "password", "CampusTest@12345");
    var created = run(body(auth(post("/api/admin/campus/users"), admin), payload), 201).get("data");
    assertEquals("STUDENT", created.get("roleType").asText());
    assertFalse(created.has("passwordHash")); assertFalse(created.has("password"));
    String id = created.get("userId").asText();
    run(body(auth(post("/api/admin/campus/users"), admin), payload), 409);
    String token = login(username, "CampusTest@12345", 200).at("/data/token").asText();
    run(body(auth(put("/api/admin/campus/users/" + id), admin), map("realName", "测试学生", "enabled", false)), 200);
    run(auth(get("/api/student/campus/guides"), token), 403);
    login(username, "CampusTest@12345", 403);
    run(body(auth(put("/api/admin/campus/users/" + id), admin), map("realName", "测试学生", "enabled", true)), 200);
    run(body(auth(put("/api/admin/campus/users/" + id + "/password"), admin), map("password", "NewCampus@98765")), 200);
    login(username, "CampusTest@12345", 401); login(username, "NewCampus@98765", 200);
    run(body(auth(put("/api/admin/campus/users/" + adminId), admin), map("realName", "管理员", "enabled", false)), 409);
    run(body(auth(put("/api/admin/campus/users/" + adminId + "/password"), admin), map("password", "NewCampus@98765")), 403);
    run(auth(get("/api/admin/campus/users"), student), 403);
    var list = run(auth(get("/api/admin/campus/users?keyword=" + username), admin), 200).get("data");
    assertEquals(1, list.get("total").asInt()); assertFalse(list.toString().contains("passwordHash"));
    payload.put("roleType", "ADMIN");
    run(body(auth(post("/api/admin/campus/users"), admin), payload), 400);
  }

  private Long exchange(String text, String answerStatus, String processing) {
    Long q = question(studentId, text, processing);
    var question = db.get(Message.class, q);
    var answer = new Message(); answer.sessionId = question.sessionId; answer.role = "ASSISTANT";
    answer.replyToMessageId = q; answer.content = "用于知识缺口测试的回答"; answer.answerStatus = answerStatus;
    db.save(answer); return answer.id;
  }

  @Test
  void gapAnalysisUsesSuccessfulAnswersAndFeedbackButExcludesTechnicalFailures() throws Exception {
    db.tx(() -> {
      exchange("休学材料缺口", "NO_MATCH", "SUCCEEDED");
      exchange("休学材料缺口", "NO_MATCH", "SUCCEEDED");
      exchange("证书办理缺口", "INSUFFICIENT_EVIDENCE", "SUCCEEDED");
      Long disliked = exchange("不满意的解释", "ANSWERED", "SUCCEEDED");
      Long failure = exchange("网络故障不能当知识缺口", "NO_MATCH", "FAILED");
      for (Long answer : List.of(disliked, failure)) {
        var f = new Feedback(); f.messageId = answer; f.userId = studentId; f.rating = "DISLIKE"; db.save(f);
      }
      question(studentId, "没有答案的技术失败", "FAILED");
      return null;
    });
    run(body(auth(post("/api/student/campus/tickets"), student),
        map("title", "补充缺失资料", "category", "STUDENT_STATUS", "content", "请补充学校公开条款。")), 201);
    var gap = run(auth(get("/api/admin/campus/gaps"), admin), 200).get("data");
    assertEquals(2, gap.get("noMatchCount").asInt()); assertEquals(1, gap.get("insufficientCount").asInt());
    assertEquals(1, gap.get("dislikeCount").asInt()); assertEquals(1, gap.get("openTicketCount").asInt());
    assertEquals(3, gap.get("items").size()); assertEquals(2, gap.at("/items/0/count").asInt());
    assertFalse(gap.get("items").toString().contains("网络故障"));
    run(auth(get("/api/admin/campus/gaps"), student), 403);
  }
}
