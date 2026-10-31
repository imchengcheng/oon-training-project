package edu.campus.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.*;
import edu.campus.rag.common.config.*;
import edu.campus.rag.entity.*;
import edu.campus.rag.entity.Document;
import edu.campus.rag.entity.Session;
import edu.campus.rag.mapper.DataMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = MysqlTestDatabaseGuard.class)
@Transactional
class KnowledgeContractTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired DataMapper db;
  @Autowired JwtEncoder encoder;
  @Autowired AppProperties settings;
  Account admin, student, other;
  String token, otherToken, adminToken;
  Document document;

  @BeforeEach
  void setup() {
    admin = db.one(Account.class, "from Account where username='admin'");
    student = db.one(Account.class, "from Account where username='stu2023001'");
    other = db.one(Account.class, "from Account where username='stu2023002'");
    student.enabled = true; other.enabled = true; admin.enabled = true;
    token = SecurityConfig.token(encoder, settings, student);
    otherToken = SecurityConfig.token(encoder, settings, other);
    adminToken = SecurityConfig.token(encoder, settings, admin);
    document = new Document();
    document.title = "测试规章-" + UUID.randomUUID(); document.category = "ACADEMIC_AFFAIRS";
    document.uploaderId = admin.id; document.filePath = "./target/knowledge-fixture.txt";
    document.fileType = "TXT"; document.activeGeneration = UUID.randomUUID().toString();
    document.vectorStatus = "COMPLETED"; document.chunkCount = 1;
    db.save(document);
    var chunk = new Chunk();
    chunk.docId = document.id; chunk.generation = document.activeGeneration; chunk.chunkIndex = 0;
    chunk.chunkText = "第一条 因病申请缓考须提交医院诊断证明。";
    chunk.sectionLabel = "第一条"; chunk.embeddingModel = "demo-character-bigram-v1"; chunk.embeddingDimension = 256;
    db.save(chunk);
  }

  JsonNode run(MockHttpServletRequestBuilder request, int expected) throws Exception {
    return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
  MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String key) { return request.header("Authorization", "Bearer " + key); }
  MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value) throws Exception {
    return request.contentType("application/json").content(json.writeValueAsBytes(value));
  }

  @Test
  void librarySearchReadsOnlyPublishedActiveGenerationAndRejectsOtherRoles() throws Exception {
    var listed = run(auth(get("/api/student/campus/documents").param("keyword", "医院诊断"), token), 200);
    assertTrue(listed.at("/data/records").toString().contains(document.id.toString()));
    var detail = run(auth(get("/api/student/campus/documents/" + document.id), token), 200);
    assertEquals("第一条", detail.at("/data/sections/0/sectionLabel").asText());
    assertFalse(detail.at("/data").has("filePath"));
    run(get("/api/student/campus/documents"), 401);
    run(auth(get("/api/student/campus/documents"), adminToken), 403);
    document.lifecycleStatus = "DELETED"; db.flush();
    run(auth(get("/api/student/campus/documents/" + document.id), token), 404);
  }

  @Test
  void bookmarksAreIdempotentPrivateAndRetainUnavailableSnapshots() throws Exception {
    var input = Map.of("targetType", "DOCUMENT", "targetId", document.id.toString(), "note", "办事前核对版本");
    var a = run(body(auth(put("/api/student/campus/favorites"), token), input), 200);
    var b = run(body(auth(put("/api/student/campus/favorites"), token), input), 200);
    String favorite = a.at("/data/favoriteId").asText();
    assertEquals(favorite, b.at("/data/favoriteId").asText());
    var otherList = run(auth(get("/api/student/campus/favorites").param("keyword", document.title), otherToken), 200);
    assertEquals(0, otherList.at("/data/total").asInt());
    run(body(auth(patch("/api/student/campus/favorites/" + favorite), otherToken), Map.of("note", "不该修改")), 404);
    run(auth(delete("/api/student/campus/favorites/" + favorite), otherToken), 404);
    document.lifecycleStatus = "DELETED"; db.flush();
    var saved = run(auth(get("/api/student/campus/favorites").param("keyword", document.title), token), 200);
    assertFalse(saved.at("/data/records/0/available").asBoolean());
    assertEquals("办事前核对版本", saved.at("/data/records/0/note").asText());
    run(body(auth(patch("/api/student/campus/favorites/" + favorite), token), Map.of("note", "x".repeat(501))), 400);
  }

  @Test
  void answerFavoritesHistoryRenameAndExportEnforceSessionOwnership() throws Exception {
    var session = new Session(); session.userId = student.id; session.title = "缓考咨询"; session.lastActiveTime = OffsetDateTime.now(); db.save(session);
    var question = new Message(); question.sessionId = session.id; question.role = "USER"; question.content = "缓考需要什么材料？";
    question.processingStatus = "SUCCEEDED"; question.clientRequestId = UUID.randomUUID().toString(); db.save(question);
    var answer = new Message(); answer.sessionId = session.id; answer.role = "ASSISTANT"; answer.content = "需要医院证明。[1]";
    answer.replyToMessageId = question.id; answer.answerStatus = "ANSWERED"; db.save(answer);
    var source = new Source(); source.messageId = answer.id; source.docId = document.id; source.chunkId = 1L;
    source.rankNo = 1; source.docTitleSnapshot = document.title; source.chunkTextSnapshot = "历史引用快照：医院诊断证明"; db.save(source);
    var input = Map.of("targetType", "ANSWER", "targetId", answer.id.toString());
    run(body(auth(put("/api/student/campus/favorites"), otherToken), input), 404);
    var saved = run(body(auth(put("/api/student/campus/favorites"), token), input), 200);
    assertEquals(session.id.toString(), saved.at("/data/sessionId").asText());
    run(body(auth(put("/api/student/campus/sessions/" + session.id), otherToken), Map.of("title", "无权修改")), 404);
    run(body(auth(put("/api/student/campus/sessions/" + session.id), token), Map.of("title", "我的缓考材料")), 200);
    var history = run(auth(get("/api/student/campus/sessions").param("keyword", "缓考材料"), token), 200);
    assertEquals(2, history.at("/data/records/0/messageCount").asInt());
    var exported = run(auth(get("/api/student/campus/sessions/" + session.id + "/export"), token), 200);
    assertTrue(exported.at("/data/markdown").asText().contains(source.chunkTextSnapshot));
    run(auth(get("/api/student/campus/sessions/" + session.id + "/export"), otherToken), 404);
  }
}
