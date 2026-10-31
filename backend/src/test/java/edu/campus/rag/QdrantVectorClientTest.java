package edu.campus.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.common.exception.BusinessException;
import edu.campus.rag.entity.Chunk;
import edu.campus.rag.service.support.QdrantVectorClient;
import edu.campus.rag.service.support.VectorStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.*;

class QdrantVectorClientTest {
  record Request(String method, String path, String query, String apiKey, JsonNode body) {}

  record Reply(int status, Object body) {}

  final ObjectMapper json = new ObjectMapper();
  final List<Request> requests = new CopyOnWriteArrayList<>();
  HttpServer server;
  ExecutorService executor;
  AppProperties properties;
  QdrantVectorClient client;
  volatile Function<Request, Reply> route;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    route = request -> request.method.equals("GET") ? config(3, "Cosine") : completed();
    server.createContext(
        "/collections/",
        exchange -> {
          var body = exchange.getRequestBody().readAllBytes();
          var request =
              new Request(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestURI().getRawQuery(),
                  exchange.getRequestHeaders().getFirst("api-key"),
                  body.length == 0 ? json.nullNode() : json.readTree(body));
          requests.add(request);
          Reply reply = route.apply(request);
          byte[] bytes =
              reply.body instanceof String text
                  ? text.getBytes(StandardCharsets.UTF_8)
                  : json.writeValueAsBytes(reply.body);
          try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
    client = new QdrantVectorClient(properties, json);
  }

  static AppProperties properties(String url) {
    var p = new AppProperties();
    p.qdrantUrl = url;
    p.qdrantTimeoutSeconds = 1;
    p.vectorStore = "qdrant";
    p.vectorNamespace = "test-protocol";
    p.modelMode = "demo";
    p.modelBaseUrl = "demo";
    p.embeddingModel = "test-embed";
    p.embeddingDimension = 3;
    return p;
  }

  static Reply config(int size, String distance) {
    return new Reply(
        200,
        Map.of(
            "status",
            "ok",
            "result",
            Map.of(
                "config",
                Map.of("params", Map.of("vectors", Map.of("size", size, "distance", distance))))));
  }

  static Reply completed() {
    return new Reply(200, Map.of("status", "ok", "result", Map.of("status", "completed")));
  }

  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  Chunk chunk(long id, long docId, String generation) {
    var chunk = new Chunk();
    chunk.id = id;
    chunk.docId = docId;
    chunk.generation = generation;
    return chunk;
  }

  @Test
  void createsMissingCollectionAndValidatesCosineDimension() {
    route =
        r ->
            r.method.equals("GET") && requests.size() == 1
                ? new Reply(404, Map.of("status", "missing"))
                : r.method.equals("GET")
                    ? config(3, "Cosine")
                    : new Reply(200, Map.of("status", "ok"));
    client.initialize();
    assertEquals(List.of("GET", "PUT", "GET"), requests.stream().map(Request::method).toList());
    assertEquals(3, requests.get(1).body.at("/vectors/size").asInt());
    assertEquals("Cosine", requests.get(1).body.at("/vectors/distance").asText());
  }

  @Test
  void acceptsConcurrentCollectionCreationOnlyAfterValidation() {
    route =
        r ->
            r.method.equals("GET") && requests.size() == 1
                ? new Reply(404, Map.of("status", "missing"))
                : r.method.equals("GET")
                    ? config(3, "Cosine")
                    : new Reply(409, Map.of("status", "exists"));
    client.initialize();
    assertEquals(3, requests.size());
  }

  @Test
  void upsertUsesStableUuidAndStringBigintPayloadWithSynchronousWrite() {
    properties.qdrantApiKey = "protocol-test-key";
    client = new QdrantVectorClient(properties, json);
    var chunk = chunk(9007199254740993L, 9007199254740995L, "generation-a");
    client.put(chunk, new float[] {1, 0, 0});
    client.put(chunk, new float[] {0, 1, 0});
    var first = requests.get(1);
    var second = requests.get(3);
    assertEquals("wait=true", first.query);
    assertEquals("protocol-test-key", first.apiKey);
    assertDoesNotThrow(() -> UUID.fromString(first.body.at("/points/0/id").asText()));
    assertEquals(first.body.at("/points/0/id"), second.body.at("/points/0/id"));
    assertEquals("9007199254740993", first.body.at("/points/0/payload/chunkId").asText());
    assertEquals("9007199254740995", first.body.at("/points/0/payload/docId").asText());
    assertEquals("generation-a", first.body.at("/points/0/payload/generation").asText());
  }

  @Test
  void exactCountsScopeGenerationAndAllDocumentGenerations() {
    route =
        r ->
            r.method.equals("GET")
                ? config(3, "Cosine")
                : new Reply(200, Map.of("status", "ok", "result", Map.of("count", 2)));
    assertEquals(2, client.count(42L, "old"));
    assertTrue(requests.get(1).body.path("exact").asBoolean());
    assertEquals("42", requests.get(1).body.at("/filter/must/0/match/value").asText());
    assertEquals("old", requests.get(1).body.at("/filter/must/1/match/value").asText());
    client.count(42L, null);
    assertEquals(1, requests.get(3).body.at("/filter/must").size());
  }

  @Test
  void queryReturnsCosineScoresAndLosslessChunkIds() {
    route =
        r ->
            r.method.equals("GET")
                ? config(3, "Cosine")
                : new Reply(
                    200,
                    Map.of(
                        "status",
                        "ok",
                        "result",
                        Map.of(
                            "points",
                            List.of(
                                Map.of(
                                    "score",
                                    0.875,
                                    "payload",
                                    Map.of("chunkId", "9007199254740993"))))));
    var hits = client.search(new float[] {1, 0, 0}, 5);
    assertEquals(9007199254740993L, hits.get(0).chunkId());
    assertEquals(0.875, hits.get(0).score());
    assertTrue(requests.get(1).path.endsWith("/points/query"));
    assertTrue(requests.get(1).body.path("with_payload").asBoolean());
    assertTrue(requests.get(1).body.at("/params/exact").asBoolean());
  }

  @Test
  void deleteFiltersDocumentAndOptionalGenerationAndWaits() {
    client.delete(7L, "old");
    client.delete(7L, null);
    assertEquals("wait=true", requests.get(1).query);
    assertEquals(2, requests.get(1).body.at("/filter/must").size());
    assertEquals(1, requests.get(3).body.at("/filter/must").size());
    assertEquals("7", requests.get(3).body.at("/filter/must/0/match/value").asText());
  }

  @Test
  void rejectsWrongDimensionBeforeMutatingCollection() {
    route = r -> config(1536, "Cosine");
    assertEquals(
        "Q-0004",
        assertThrows(
                BusinessException.class, () -> client.put(chunk(1, 1, "a"), new float[] {1, 0, 0}))
            .code);
    assertEquals(1, requests.size());
  }

  @Test
  void pendingWritesAndHttpFailuresAreTechnicalFailures() {
    route =
        r ->
            r.method.equals("GET")
                ? config(3, "Cosine")
                : new Reply(
                    200, Map.of("status", "ok", "result", Map.of("status", "acknowledged")));
    assertEquals(
        "Q-0004", assertThrows(BusinessException.class, () -> client.delete(1L, null)).code);
    route = r -> new Reply(401, Map.of("status", "unauthorized"));
    assertEquals(503, assertThrows(BusinessException.class, client::initialize).status);
    route = r -> new Reply(200, "invalid JSON");
    assertEquals("Q-0004", assertThrows(BusinessException.class, client::initialize).code);
  }

  @Test
  void timeoutDoesNotBecomeAnEmptySuccessfulSearch() {
    route =
        r -> {
          try {
            Thread.sleep(1600);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          return config(3, "Cosine");
        };
    assertEquals(
        "Q-0004",
        assertThrows(BusinessException.class, () -> client.search(new float[] {1, 0, 0}, 5)).code);
  }

  @Test
  void rejectsBadVectorsBeforeAnyRequestAndRejectsUnknownStore() {
    var store = new VectorStore(properties, client);
    assertEquals(
        "Q-0006",
        assertThrows(BusinessException.class, () -> store.search(new float[] {1, 0}, 5)).code);
    assertEquals(
        "Q-0006",
        assertThrows(
                BusinessException.class,
                () -> store.put(chunk(1, 1, "a"), new float[] {Float.NaN, 0, 0}))
            .code);
    assertTrue(requests.isEmpty());
    properties.vectorStore = "typo";
    assertThrows(BusinessException.class, () -> new VectorStore(properties, client));
  }

  @Test
  void collectionIdentityIsStableAndSeparatesNamespaceModelAndDimensions() {
    String initial = client.collectionName();
    assertEquals(initial, new QdrantVectorClient(properties, json).collectionName());
    properties.vectorNamespace = "test-another";
    assertNotEquals(initial, new QdrantVectorClient(properties, json).collectionName());
    properties.vectorNamespace = "test-protocol";
    properties.embeddingDimension = 1536;
    assertNotEquals(initial, new QdrantVectorClient(properties, json).collectionName());
    properties.embeddingDimension = 3;
    properties.embeddingModel = "different-model";
    assertNotEquals(initial, new QdrantVectorClient(properties, json).collectionName());
  }

  @Test
  void changingChatProviderKeepsLocalEmbeddingCollectionAndSubsetUsesStringIds() {
    String initial = client.collectionName();
    properties.modelMode = "deepseek";
    properties.modelBaseUrl = "https://api.deepseek.com";
    properties.embeddingMode = "local";
    assertEquals(initial, new QdrantVectorClient(properties, json).collectionName());
    route = r -> r.method.equals("GET") ? config(3, "Cosine")
        : new Reply(200, Map.of("status", "ok", "result", Map.of("points", List.of())));
    client.searchSubset(new float[] {1, 0, 0}, List.of(9007199254740993L));
    var body = requests.get(requests.size() - 1).body;
    assertEquals("chunkId", body.at("/filter/must/0/key").asText());
    assertEquals("9007199254740993", body.at("/filter/must/0/match/any/0").asText());
    assertTrue(body.at("/params/exact").asBoolean());
  }
}
