package edu.campus.rag;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.common.exception.BusinessException;
import edu.campus.rag.entity.Chunk;
import edu.campus.rag.service.support.ModelGateway;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.*;

class DeepSeekGatewayTest {
  HttpServer server;
  ObjectMapper json = new ObjectMapper();
  AppProperties settings;
  JsonNode request;
  String responseText = "{\"sufficient\":true,\"answer\":\"提交材料。[1]\"}";
  String finish = "stop";
  int status = 200;
  String embeddingAuth;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      request = json.readTree(exchange.getRequestBody());
      byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of(
          "finish_reason", finish, "message", Map.of("content", responseText)))));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.createContext("/embedding-provider/embeddings", exchange -> {
      embeddingAuth = exchange.getRequestHeaders().getFirst("Authorization");
      exchange.getRequestBody().readAllBytes();
      byte[] bytes = json.writeValueAsBytes(Map.of("data", List.of(Map.of(
          "index", 0, "object", "embedding", "embedding", List.of(1.0, 0.0, 0.0)))));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    settings = new AppProperties();
    settings.modelMode = "deepseek";
    settings.modelBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    settings.modelApiKey = "test-chat-key";
    settings.chatModel = "deepseek-flash";
    settings.embeddingMode = "local";
    settings.embeddingModel = "demo-character-bigram-v1";
    settings.embeddingDimension = 256;
    settings.topK = 5;
    settings.similarityThreshold = .13;
    settings.modelContextTokens = 16384;
  }

  @AfterEach
  void stop() { server.stop(0); }

  ModelGateway.Draft generate(ModelGateway gateway) {
    var chunk = new Chunk();
    chunk.chunkText = "第一条 申请休学应当提交证明材料。";
    return gateway.generate("需要什么材料？", List.of(new ModelGateway.Evidence(chunk, .8)));
  }

  @Test
  void localEmbeddingDoesNotCallChatAndAnswerRequestsJsonWithoutThinking() {
    var gateway = new ModelGateway(settings, json);
    assertEquals(256, gateway.embed("休学材料").length);
    assertNull(request);
    assertTrue(generate(gateway).sufficient());
    assertEquals("deepseek-flash", request.path("model").asText());
    assertEquals("disabled", request.at("/thinking/type").asText());
    assertEquals("json_object", request.at("/response_format/type").asText());
    assertEquals("system", request.at("/messages/0/role").asText());
  }

  @Test
  void rewriteUsesPlainTextAndIndependentEmbeddingProviderUsesItsOwnKey() {
    settings.embeddingMode = "openai";
    settings.embeddingBaseUrl = settings.modelBaseUrl + "/embedding-provider";
    settings.embeddingApiKey = "test-embedding-key";
    settings.embeddingModel = "separate-embedding";
    settings.embeddingDimension = 3;
    var gateway = new ModelGateway(settings, json);
    assertArrayEquals(new float[] {1, 0, 0}, gateway.embed("问题"));
    assertEquals("Bearer test-embedding-key", embeddingAuth);
    responseText = "申请休学需要提交什么材料？";
    assertEquals(responseText, gateway.rewrite("那材料呢？", List.of("学生：休学")));
    assertFalse(request.has("response_format"));
  }

  @Test
  void providerFailuresTruncationAndTrailingJsonAreRejectedWithoutLeakingBodies() {
    var gateway = new ModelGateway(settings, json);
    for (int code : List.of(401, 402, 429, 500)) {
      status = code;
      responseText = "private-provider-payload";
      var failure = assertThrows(BusinessException.class, () -> generate(gateway));
      assertEquals("Q-0005", failure.code);
      assertFalse(failure.getMessage().contains(responseText));
    }
    status = 200;
    finish = "length";
    assertEquals("Q-0010", assertThrows(BusinessException.class, () -> generate(gateway)).code);
    finish = "stop";
    responseText = "{\"sufficient\":true,\"answer\":\"a [1]\"} {}";
    assertEquals("Q-0010", assertThrows(BusinessException.class, () -> generate(gateway)).code);
  }

  @Test
  void invalidLocalEmbeddingModelIsRejectedRatherThanSilentlyHashingAsSemanticModel() {
    settings.embeddingModel = "text-embedding-3-small";
    assertThrows(BusinessException.class, () -> new ModelGateway(settings, json));
  }
}
