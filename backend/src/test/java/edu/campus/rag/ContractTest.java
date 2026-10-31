package edu.campus.rag;

import static edu.campus.rag.common.TimeUtils.now;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import edu.campus.rag.common.Api;
import edu.campus.rag.common.bootstrap.StartupRunner;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.entity.*;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.DocumentService;
import edu.campus.rag.service.QuestionService;
import edu.campus.rag.service.support.ModelGateway;
import edu.campus.rag.service.support.VectorStore;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = MysqlTestDatabaseGuard.class)
class ContractTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired DocumentService documents;
  @Autowired QuestionService questions;
  @Autowired StartupRunner startup;
  @Autowired AppProperties settings;
  @Autowired JwtEncoder encoder;
  @MockitoSpyBean ModelGateway model;
  @MockitoSpyBean VectorStore vectors;
  @MockitoSpyBean DataMapper db;
  String admin, student, other;
  static final String TEXT =
      "第一条 学生因病申请休学，应当提交二级甲等以上医院诊断证明，经学院审核后报教务处批准。\n"
          + "第二条 休学申请应提交休学申请表、医院证明和家长知情同意材料。\n"
          + "第三条 休学期满申请复学，应提前向教务处提交复学申请。";

  @BeforeEach
  void setup() throws Exception {
    reset(model, vectors, db);
    settings.questionTimeoutSeconds = 3;
    settings.taskTimeoutSeconds = 600;
    settings.loginLimit = 10000;
    settings.questionLimit = 10000;
    for (var d : db.tx(() -> db.list(Document.class, "from Document"))) vectors.delete(d.id, null);
    db.tx(
        () -> {
          for (String name :
              List.of("Source", "Feedback", "Message", "Session", "Chunk", "Document"))
            db.executeUpdate("delete from " + name);
          return null;
        });
    admin = login("admin", "Admin@123456");
    student = login("stu2023001", "Student@123456");
    other = login("stu2023002", "Student@123456");
  }

  JsonNode run(MockHttpServletRequestBuilder request, int status) throws Exception {
    return json.readTree(
        mvc.perform(request)
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8));
  }

  MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) {
    try {
      return request.contentType("application/json").content(json.writeValueAsBytes(body));
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  String login(String u, String p) throws Exception {
    return run(body(post("/api/auth/login"), Map.of("username", u, "password", p)), 200)
        .at("/data/token")
        .asText();
  }

  MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r, String token) {
    return r.header("Authorization", "Bearer " + token);
  }

  String session() throws Exception {
    return run(auth(post("/api/qa/sessions"), student), 201).at("/data/sessionId").asText();
  }

  JsonNode ask(String session, String request, String content, int status) throws Exception {
    return run(
        body(
            auth(post("/api/qa/sessions/" + session + "/questions"), student),
            Map.of("clientRequestId", request, "content", content)),
        status);
  }

  JsonNode ask(String session, String content) throws Exception {
    return ask(session, UUID.randomUUID().toString(), content, 200).get("data");
  }

  Long upload() throws Exception {
    var file =
        new MockMultipartFile(
            "file", "休学办法.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8));
    var result =
        run(
            auth(
                multipart("/api/admin/documents")
                    .file(file)
                    .param("title", "休学管理办法（测试示例）")
                    .param("category", "STUDENT_STATUS"),
                admin),
            202);
    Long id = Long.valueOf(result.at("/data/docId").asText());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "COMPLETED".equals(documents.detail(id).get("vectorStatus")));
    return id;
  }

  JsonNode history(String s) throws Exception {
    return run(auth(get("/api/qa/sessions/" + s), student), 200).at("/data/messages/records");
  }

  @Test
  void localLoginOriginsAcceptedAndOtherOriginsRejected() throws Exception {
    for (String origin :
        List.of("http://localhost:5173", "http://127.0.0.1:5173")) {
      mvc.perform(
              options("/api/auth/login")
                  .header("Origin", origin)
                  .header("Access-Control-Request-Method", "POST")
                  .header("Access-Control-Request-Headers", "authorization,content-type"))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", origin))
          .andExpect(header().string("Access-Control-Max-Age", "3600"))
          .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
      mvc.perform(
              body(post("/api/auth/login"), Map.of("username", "admin", "password", "Admin@123456"))
                  .header("Origin", origin))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", origin))
          .andExpect(jsonPath("$.code").value("0"));
      mvc.perform(get("/api/admin/documents").header("Origin", origin))
          .andExpect(status().isUnauthorized());
    }
    for (String origin :
        List.of("http://127.0.0.1", "http://127.0.0.1:5174", "http://127.0.0.1.evil.example", "https://example.com", "null")) {
      mvc.perform(
              body(post("/api/auth/login"), Map.of("username", "admin", "password", "Admin@123456"))
                  .header("Origin", origin))
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
          .andExpect(content().string("Invalid CORS request"));
      mvc.perform(options("/api/auth/login")
              .header("Origin", origin)
              .header("Access-Control-Request-Method", "POST"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(options("/api/auth/login")
            .header("Origin", "http://127.0.0.1:5173")
            .header("Access-Control-Request-Method", "TRACE"))
        .andExpect(status().isForbidden());
    mvc.perform(options("/api/auth/login")
            .header("Origin", "http://127.0.0.1:5173")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "X-Unapproved-Header"))
        .andExpect(status().isForbidden());
  }

  @Test
  void hybridRecoversKeywordEvidenceBelowVectorThresholdAndExcludesDeletedDocuments() throws Exception {
    String previousMode = settings.retrievalMode;
    double previousThreshold = settings.similarityThreshold;
    try {
      Long id = upload();
      settings.similarityThreshold = 1;
      settings.retrievalMode = "vector";
      assertEquals("NO_MATCH", ask(session(), "休学申请表").path("answerStatus").asText());
      settings.retrievalMode = "hybrid";
      var answer = ask(session(), "休学申请表");
      assertEquals("ANSWERED", answer.path("answerStatus").asText());
      assertFalse(answer.path("sources").isEmpty());
      documents.delete(id);
      assertEquals("NO_MATCH", ask(session(), "休学申请表").path("answerStatus").asText());
      var summary = run(auth(get("/api/admin/monitor/summary"), admin), 200);
      assertEquals("hybrid", summary.at("/data/runtime/retrievalMode").asText());
      assertFalse(summary.at("/data/runtime").has("modelApiKey"));
    } finally {
      settings.retrievalMode = previousMode;
      settings.similarityThreshold = previousThreshold;
    }
  }

  @Test
  void authenticationAndRoleIsolation() throws Exception {
    run(get("/api/qa/sessions"), 401);
    run(auth(get("/api/admin/documents"), student), 403);
    run(auth(get("/api/qa/sessions"), admin), 403);
    assertEquals(
        "A-0001",
        run(body(post("/api/auth/login"), Map.of("username", "nobody", "password", "wrong")), 401)
            .get("code")
            .asText());
    assertEquals(
        "A-0002",
        run(auth(get("/api/qa/sessions"), student + "tampered"), 401).get("code").asText());
  }

  @Test
  void expiredAndDisabledAccountsRejected() throws Exception {
    var a = db.tx(() -> db.one(Account.class, "from Account where username='stu2023002'"));
    var claims =
        JwtClaimsSet.builder()
            .subject(a.id.toString())
            .issuedAt(Instant.now().minusSeconds(200))
            .expiresAt(Instant.now().minusSeconds(100))
            .build();
    String expired =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    run(auth(get("/api/qa/sessions"), expired), 401);
    db.tx(
        () -> {
          db.get(Account.class, a.id).enabled = false;
          return null;
        });
    try {
      run(auth(get("/api/qa/sessions"), other), 403);
      run(
          body(
              post("/api/auth/login"),
              Map.of("username", a.username, "password", "Student@123456")),
          403);
    } finally {
      db.tx(
          () -> {
            db.get(Account.class, a.id).enabled = true;
            return null;
          });
    }
  }

  @Test
  void unknownControlledFieldsAndPaginationRejected() throws Exception {
    run(body(auth(post("/api/qa/sessions"), student), Map.of("userId", "1")), 400);
    run(
        body(
            post("/api/auth/login"),
            Map.of("username", "admin", "password", "Admin@123456", "roleType", "ADMIN")),
        400);
    run(auth(get("/api/qa/sessions?page=0"), student), 400);
    run(auth(get("/api/admin/documents?size=101"), admin), 400);
    run(auth(get("/api/admin/documents?vectorStatus=BAD"), admin), 400);
  }

  @Test
  void noMatchIsPersistedAndReplaySurvivesClose() throws Exception {
    String s = session(), request = UUID.randomUUID().toString();
    var answer = ask(s, request, "完全没有知识库", 200).get("data");
    assertEquals("NO_MATCH", answer.get("answerStatus").asText());
    assertFalse(answer.get("matched").asBoolean());
    assertEquals("未找到相关规章条款", answer.at("/assistantMessage/content").asText());
    assertEquals(2, history(s).size());
    run(auth(put("/api/qa/sessions/" + s + "/close"), student), 200);
    assertEquals(
        answer.at("/assistantMessage/messageId"),
        ask(s, request, "完全没有知识库", 200).at("/data/assistantMessage/messageId"));
    ask(s, UUID.randomUUID().toString(), "新问题", 409);
    verify(model, never()).generate(anyString(), anyList());
  }

  @Test
  void answeredSourcesAndOwnershipSnapshots() throws Exception {
    Long doc = upload();
    String s = session();
    var answer = ask(s, "申请休学需要什么证明？");
    assertEquals("ANSWERED", answer.get("answerStatus").asText());
    assertTrue(answer.get("sources").size() > 0);
    assertTrue(answer.at("/assistantMessage/content").asText().contains("[1]"));
    String message = answer.at("/assistantMessage/messageId").asText(),
        source = answer.at("/sources/0/sourceId").asText();
    var original =
        run(auth(get("/api/qa/messages/" + message + "/sources/" + source), student), 200)
            .get("data");
    assertTrue(original.has("fullText"));
    assertTrue(original.get("pageStart").isNull());
    run(auth(get("/api/qa/sessions/" + s), other), 404);
    run(auth(put("/api/qa/sessions/" + s + "/close"), other), 404);
    run(
        body(
            auth(post("/api/qa/sessions/" + s + "/questions"), other),
            Map.of("clientRequestId", UUID.randomUUID().toString(), "content", "你好")),
        404);
    run(auth(get("/api/qa/messages/" + message + "/sources/" + source), other), 404);
    run(
        body(
            auth(put("/api/qa/messages/" + message + "/feedback"), other),
            Map.of("rating", "LIKE")),
        404);
    documents.patch(doc, Map.of("title", "新展示标题"));
    assertEquals(
        original.get("docTitle"),
        run(auth(get("/api/qa/messages/" + message + "/sources/" + source), student), 200)
            .at("/data/docTitle"));
    doThrow(Api.fail(503, "Q-0004", "offline")).when(vectors).search(any(float[].class), anyInt());
    run(auth(get("/api/qa/messages/" + message + "/sources/" + source), student), 200);
  }

  @Test
  void insufficientEvidenceIsSavedWithoutFalseSources() throws Exception {
    upload();
    doReturn(new ModelGateway.Draft(false, "")).when(model).generate(anyString(), anyList());
    var result = ask(session(), "申请休学需要什么证明？");
    assertEquals("INSUFFICIENT_EVIDENCE", result.get("answerStatus").asText());
    assertTrue(result.get("matched").asBoolean());
    assertEquals(0, result.get("sources").size());
    assertEquals("现有规章条款不足以回答该问题", result.at("/assistantMessage/content").asText());
  }

  @Test
  void illegalCitationsFailAndRetryReusesUserMessage() throws Exception {
    upload();
    String s = session(), request = UUID.randomUUID().toString();
    doReturn(new ModelGateway.Draft(true, "伪造引用 [99]"))
        .when(model)
        .generate(anyString(), anyList());
    var failed = ask(s, request, "申请休学需要什么证明？", 502);
    assertEquals("Q-0010", failed.get("code").asText());
    assertEquals(1, history(s).size());
    assertEquals("FAILED", history(s).get(0).get("processingStatus").asText());
    doCallRealMethod().when(model).generate(anyString(), anyList());
    var good = ask(s, request, "申请休学需要什么证明？", 200);
    assertEquals(failed.at("/data/userMessageId"), good.at("/data/userMessage/messageId"));
    assertEquals(2, history(s).get(0).get("attemptCount").asInt());
    assertEquals(2, history(s).size());
  }

  @Test
  void idempotencyConflictAndSuccessfulReplayDoNotCallModelTwice() throws Exception {
    upload();
    String s = session(), req = UUID.randomUUID().toString();
    ask(s, req, "申请休学需要什么证明？", 200);
    ask(s, req, "申请休学需要什么证明？", 200);
    ask(s, req, "不同问题", 409);
    verify(model, times(1)).generate(anyString(), anyList());
    assertEquals(2, history(s).size());
  }

  @Test
  void failedOlderQuestionCannotBeRetried() throws Exception {
    String s = session(), id = UUID.randomUUID().toString();
    doThrow(Api.fail(502, "Q-0006", "offline")).when(model).embed(anyString());
    ask(s, id, "休学", 502);
    doCallRealMethod().when(model).embed(anyString());
    ask(s, "新问题");
    assertEquals("Q-0008", ask(s, id, "休学", 409).get("code").asText());
  }

  @Test
  void inputUnicodeAndUuidBoundaries() throws Exception {
    String s = session();
    ask(s, "bad", "休学", 400);
    ask(s, UUID.randomUUID().toString(), "  ", 400);
    ask(s, UUID.randomUUID().toString(), "学".repeat(1001), 400);
    ask(s, UUID.randomUUID().toString(), "😀".repeat(1000), 200);
    assertEquals(2, history(s).size());
  }

  @Test
  void concurrentSameSessionAndCloseAreRejected() throws Exception {
    upload();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            i -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return i.callRealMethod();
            })
        .when(model)
        .generate(anyString(), anyList());
    String s = session(), id = UUID.randomUUID().toString();
    var pool = Executors.newSingleThreadExecutor();
    try {
      var first = pool.submit(() -> ask(s, id, "申请休学需要什么证明？", 200));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      ask(s, id, "申请休学需要什么证明？", 409);
      ask(s, UUID.randomUUID().toString(), "另一个问题", 409);
      run(auth(put("/api/qa/sessions/" + s + "/close"), student), 409);
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
      assertEquals(2, history(s).size());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void timeoutNeverPublishesLateAnswer() throws Exception {
    upload();
    settings.questionTimeoutSeconds = 1;
    doAnswer(
            i -> {
              try {
                Thread.sleep(1800);
              } catch (InterruptedException ignored) {
              }
              return i.callRealMethod();
            })
        .when(model)
        .generate(anyString(), anyList());
    String s = session();
    var result = ask(s, UUID.randomUUID().toString(), "申请休学需要什么证明？", 504);
    assertEquals("Q-0009", result.get("code").asText());
    assertEquals(1, history(s).size());
    assertEquals("FAILED", history(s).get(0).get("processingStatus").asText());
  }

  @Test
  void dependencyFailureIsNotNoMatch() throws Exception {
    String s = session();
    doThrow(Api.fail(503, "Q-0004", "检索故障")).when(vectors).search(any(float[].class), anyInt());
    assertEquals("Q-0004", ask(s, UUID.randomUUID().toString(), "休学", 503).get("code").asText());
    assertEquals(1, history(s).size());
  }

  @Test
  void answerAndSourcesRollbackTogetherOnSaveFailure() throws Exception {
    upload();
    doAnswer(
            i -> {
              if (i.getArgument(0) instanceof Source)
                throw new org.springframework.dao.DataAccessResourceFailureException("injected");
              return i.callRealMethod();
            })
        .when(db)
        .save(any());
    String s = session();
    ask(s, UUID.randomUUID().toString(), "申请休学需要什么证明？", 500);
    assertEquals(1, history(s).size());
    assertEquals("FAILED", history(s).get(0).get("processingStatus").asText());
    assertEquals(0, db.tx(() -> db.list(Source.class, "from Source").size()));
  }

  @Test
  void rebuildFailureRetainsActiveGeneration() throws Exception {
    Long id = upload();
    var before = documents.detail(id);
    doThrow(Api.fail(502, "Q-0006", "offline")).when(model).embed(anyString());
    documents.rebuild(id);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> "FAILED".equals(documents.detail(id).get("vectorStatus")));
    var after = documents.detail(id);
    assertEquals(before.get("activeGeneration"), after.get("activeGeneration"));
    assertEquals(true, after.get("searchable"));
    assertEquals(before.get("chunkCount"), after.get("chunkCount"));
    assertEquals("T-0004", ((Map<?, ?>) after.get("lastError")).get("code"));
  }

  @Test
  void successfulRebuildSupersedesHistoricalSources() throws Exception {
    Long id = upload();
    String s = session();
    var answer = ask(s, "申请休学需要什么证明？");
    String old = (String) documents.detail(id).get("activeGeneration");
    documents.rebuild(id);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                "COMPLETED".equals(documents.detail(id).get("vectorStatus"))
                    && !old.equals(documents.detail(id).get("activeGeneration")));
    assertEquals("SUPERSEDED", history(s).get(1).at("/sources/0/sourceStatus").asText());
    assertEquals(answer.at("/sources/0/docTitle"), history(s).get(1).at("/sources/0/docTitle"));
  }

  @Test
  void deletionPreservesHistoryAndRetriesFailedVectorCleanup() throws Exception {
    Long id = upload();
    String s = session();
    ask(s, "申请休学需要什么证明？");
    doThrow(Api.fail(503, "Q-0004", "offline")).when(vectors).delete(eq(id), isNull());
    documents.delete(id);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> documents.detail(id).get("cleanupError") != null);
    assertEquals("DELETING", documents.detail(id).get("lifecycleStatus"));
    assertEquals(false, documents.detail(id).get("searchable"));
    assertEquals("DELETED", history(s).get(1).at("/sources/0/sourceStatus").asText());
    var failedSummary = run(auth(get("/api/admin/monitor/summary"), admin), 200).get("data");
    assertEquals(1, failedSummary.get("deletingCount").asInt());
    assertEquals(0, failedSummary.get("pendingDeletionCount").asInt());
    var retryStarted = new CountDownLatch(1);
    var releaseRetry = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              retryStarted.countDown();
              assertTrue(releaseRetry.await(10, TimeUnit.SECONDS));
              return invocation.callRealMethod();
            })
        .when(vectors).delete(eq(id), isNull());
    try {
      documents.delete(id);
      assertTrue(retryStarted.await(5, TimeUnit.SECONDS));
      assertNull(documents.detail(id).get("cleanupError"));
      var retrySummary = run(auth(get("/api/admin/monitor/summary"), admin), 200).get("data");
      assertEquals(1, retrySummary.get("pendingDeletionCount").asInt());
    } finally {
      releaseRetry.countDown();
    }
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> "DELETED".equals(documents.detail(id).get("lifecycleStatus")));
    assertEquals(2, history(s).size());
    assertEquals(0, run(auth(get("/api/admin/monitor/summary"), admin), 200)
        .at("/data/pendingDeletionCount").asInt());
    run(auth(delete("/api/admin/documents/" + id), admin), 200);
  }

  @Test
  void deleteWhileEmbeddingCannotReactivateDocument() throws Exception {
    Long id = upload();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            i -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return i.callRealMethod();
            })
        .when(model)
        .embed(anyString());
    documents.rebuild(id);
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    try {
      documents.delete(id);
      assertEquals(false, documents.detail(id).get("searchable"));
    } finally {
      release.countDown();
    }
    await()
        .atMost(Duration.ofSeconds(8))
        .until(() -> "DELETED".equals(documents.detail(id).get("lifecycleStatus")));
    assertEquals(0, vectors.count(id, (String) documents.detail(id).get("activeGeneration")));
  }

  @Test
  void duplicateRebuildRejectedAndCleanupErrorDoesNotUndoActivation() throws Exception {
    Long id = upload();
    String old = (String) documents.detail(id).get("activeGeneration");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            i -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return i.callRealMethod();
            })
        .when(model)
        .embed(anyString());
    documents.rebuild(id);
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    try {
      run(auth(post("/api/admin/documents/" + id + "/revectorize"), admin), 409);
      doThrow(Api.fail(503, "Q-0004", "offline")).when(vectors).delete(id, old);
    } finally {
      release.countDown();
    }
    await()
        .atMost(Duration.ofSeconds(8))
        .until(() -> documents.detail(id).get("cleanupError") != null);
    assertEquals("COMPLETED", documents.detail(id).get("vectorStatus"));
    assertNotEquals(old, documents.detail(id).get("activeGeneration"));
    assertEquals(true, documents.detail(id).get("searchable"));
  }

  @Test
  void evidenceChangingDuringGenerationReturnsRetryableConflict() throws Exception {
    Long id = upload();
    doAnswer(
            i -> {
              documents.delete(id);
              return i.callRealMethod();
            })
        .when(model)
        .generate(anyString(), anyList());
    String s = session();
    assertEquals(
        "Q-0012", ask(s, UUID.randomUUID().toString(), "申请休学需要什么证明？", 409).get("code").asText());
    assertEquals(1, history(s).size());
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> "DELETED".equals(documents.detail(id).get("lifecycleStatus")));
  }

  @Test
  void feedbackUpsertAndAdminPrivacy() throws Exception {
    String s = session();
    var answer = ask(s, "没有文档");
    String message = answer.at("/assistantMessage/messageId").asText();
    var first =
        run(
                body(
                    auth(put("/api/qa/messages/" + message + "/feedback"), student),
                    Map.of("rating", "LIKE", "remark", "有帮助")),
                200)
            .get("data");
    var next =
        run(
                body(
                    auth(put("/api/qa/messages/" + message + "/feedback"), student),
                    Map.of("rating", "DISLIKE")),
                200)
            .get("data");
    assertEquals(first.get("feedbackId"), next.get("feedbackId"));
    assertTrue(next.get("remark").isNull());
    run(
        body(
            auth(
                put(
                    "/api/qa/messages/"
                        + answer.at("/userMessage/messageId").asText()
                        + "/feedback"),
                student),
            Map.of("rating", "LIKE")),
        400);
    run(
        body(
            auth(put("/api/qa/messages/" + message + "/feedback"), student),
            Map.of("rating", "LIKE", "remark", "字".repeat(501))),
        400);
    var records =
        run(auth(get("/api/admin/feedback?rating=DISLIKE"), admin), 200).at("/data/records");
    assertEquals(1, records.size());
    assertFalse(records.get(0).has("userId"));
    assertTrue(records.get(0).has("sources"));
  }

  @Test
  void statisticsUseLogicalQuestionsAndCorrectDenominators() throws Exception {
    String s = session(), id = UUID.randomUUID().toString();
    ask(s, id, "无文档", 200);
    ask(s, id, "无文档", 200);
    var stats = run(auth(get("/api/admin/monitor/qa"), admin), 200).get("data");
    assertEquals(1, stats.get("totalQuestions").asInt());
    assertEquals(1, stats.get("noMatchCount").asInt());
    assertEquals(0, stats.get("answerRate").asDouble());
    upload();
    var summary = run(auth(get("/api/admin/monitor/summary"), admin), 200).get("data");
    assertEquals(1, summary.get("totalDocuments").asInt());
    assertEquals(1, summary.get("completionRate").asDouble());
    run(
        auth(
            get("/api/admin/monitor/qa")
                .param("from", "2026-01-01T00:00:00+08:00")
                .param("to", "2026-10-15T00:00:00+08:00"),
            admin),
        400);
  }

  @Test
  void fileSignaturesEmptyTextAndMetadataValidation() throws Exception {
    var bad = new MockMultipartFile("file", "bad.pdf", "application/pdf", "fake".getBytes());
    run(
        auth(
            multipart("/api/admin/documents")
                .file(bad)
                .param("title", "标题")
                .param("category", "DISCIPLINE"),
            admin),
        415);
    var binary =
        new MockMultipartFile("file", "bad.txt", "text/plain", new byte[] {(byte) 0xff, 0, 1});
    run(
        auth(
            multipart("/api/admin/documents")
                .file(binary)
                .param("title", "标题")
                .param("category", "DISCIPLINE"),
            admin),
        415);
    var empty = new MockMultipartFile("file", "empty.txt", "text/plain", "  \n".getBytes());
    var uploaded =
        run(
            auth(
                multipart("/api/admin/documents")
                    .file(empty)
                    .param("title", "空白文档")
                    .param("category", "DISCIPLINE"),
                admin),
            202);
    Long doc = Long.valueOf(uploaded.at("/data/docId").asText());
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> "FAILED".equals(documents.detail(doc).get("vectorStatus")));
    assertEquals("T-0002", ((Map<?, ?>) documents.detail(doc).get("lastError")).get("code"));
    run(body(auth(patch("/api/admin/documents/" + doc), admin), Map.of()), 400);
    run(body(auth(patch("/api/admin/documents/" + doc), admin), Map.of("filePath", "hack")), 400);
  }

  @Test
  void startupRecoversInterruptedWorkAndPreservesOldGeneration() throws Exception {
    Long id = upload();
    String s = session();
    String old = (String) documents.detail(id).get("activeGeneration");
    db.tx(
        () -> {
          var d = db.get(Document.class, id);
          d.vectorStatus = "PROCESSING";
          d.taskStage = "EMBEDDING";
          var m = new Message();
          m.sessionId = Long.valueOf(s);
          m.role = "USER";
          m.content = "中断问题";
          m.clientRequestId = UUID.randomUUID().toString();
          m.processingStatus = "PROCESSING";
          m.lastAttemptTime = now();
          db.save(m);
          db.get(Session.class, Long.valueOf(s)).processingMessageId = m.id;
          return null;
        });
    startup.recover();
    assertEquals("FAILED", documents.detail(id).get("vectorStatus"));
    assertEquals(old, documents.detail(id).get("activeGeneration"));
    assertEquals("T-0007", ((Map<?, ?>) documents.detail(id).get("lastError")).get("code"));
    assertEquals("FAILED", history(s).get(0).get("processingStatus").asText());
  }

  @Test
  void startupRestoresMissingVectorsFromPersistedActiveChunks() throws Exception {
    Long id = upload();
    String generation = (String) documents.detail(id).get("activeGeneration");
    long expected = vectors.count(id, generation);
    assertTrue(expected > 0);
    vectors.delete(id, generation);
    assertEquals(0, vectors.count(id, generation));
    startup.run(new org.springframework.boot.DefaultApplicationArguments());
    assertEquals(expected, vectors.count(id, generation));
    assertEquals("ANSWERED", ask(session(), "申请休学需要什么证明？").path("answerStatus").asText());
  }

  @Test
  void multiTurnUsesOnlyCompletedHistory() throws Exception {
    upload();
    String s = session();
    ask(s, "申请休学需要什么证明？");
    ask(s, "那还需要哪些材料呢？");
    var records = history(s);
    assertTrue(records.get(2).get("rewrittenQuery").asText().contains("申请休学"));
    verify(model, times(1)).rewrite(eq("申请休学需要什么证明？"), eq(List.of()));
  }

  @Test
  void loginRateLimitReturnsRetryAfter() throws Exception {
    settings.loginLimit = 1;
    var first =
        body(
            post("/api/auth/login")
                .with(
                    r -> {
                      r.setRemoteAddr("192.0.2.27");
                      return r;
                    }),
            Map.of("username", "admin", "password", "Admin@123456"));
    run(first, 200);
    mvc.perform(
            body(
                post("/api/auth/login")
                    .with(
                        r -> {
                          r.setRemoteAddr("192.0.2.27");
                          return r;
                        }),
                Map.of("username", "admin", "password", "Admin@123456")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }
}
