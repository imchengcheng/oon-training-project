package edu.campus.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.entity.Chunk;
import edu.campus.rag.service.support.QdrantVectorClient;
import edu.campus.rag.service.support.VectorStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.*;

/** Opt-in real Qdrant tests; run via scripts/test-qdrant.ps1. Each test owns its collection. */
class QdrantIntegrationIT {
  AppProperties settings;
  QdrantVectorClient client;
  VectorStore vectors;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setup() {
    settings =
        QdrantVectorClientTest.properties(
            System.getenv().getOrDefault("QDRANT_URL", "http://127.0.0.1:6333"));
    settings.qdrantTimeoutSeconds = 5;
    settings.qdrantApiKey = System.getenv().getOrDefault("QDRANT_API_KEY", "");
    settings.vectorNamespace = "test-it-" + UUID.randomUUID();
    client = new QdrantVectorClient(settings, json);
    vectors = new VectorStore(settings, client);
    vectors.initialize();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (client == null) return;
    assertTrue(client.collectionName().startsWith("campus_test-it-"));
    var builder =
        HttpRequest.newBuilder(
                URI.create(
                    settings.qdrantUrl.replaceAll("/+$", "")
                        + "/collections/"
                        + client.collectionName()))
            .timeout(Duration.ofSeconds(5))
            .DELETE();
    if (!settings.qdrantApiKey.isBlank()) builder.header("api-key", settings.qdrantApiKey);
    var response =
        HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() == 200 || response.statusCode() == 404);
  }

  Chunk chunk(long id, long doc, String generation) {
    var c = new Chunk();
    c.id = id;
    c.docId = doc;
    c.generation = generation;
    return c;
  }

  @Test
  void upsertSearchAndNewClientSeePersistedVectors() {
    var c = chunk(9007199254740993L, 10, "a");
    vectors.put(c, new float[] {1, 0, 0});
    vectors.put(c, new float[] {0, 1, 0});
    vectors.put(chunk(2, 10, "a"), new float[] {1, 0, 0});
    assertEquals(2, vectors.count(10L, "a"));
    var reopened = new VectorStore(settings, new QdrantVectorClient(settings, json));
    assertEquals(2, reopened.count(10L, "a"));
    var hits = reopened.search(new float[] {0, 1, 0}, 2);
    assertEquals(c.id, hits.get(0).chunkId());
    assertEquals(1, hits.get(0).score(), 0.00001);
  }

  @Test
  void generationDeletionPreservesNewGenerationAndOtherDocuments() {
    vectors.put(chunk(1, 10, "old"), new float[] {1, 0, 0});
    vectors.put(chunk(2, 10, "new"), new float[] {0, 1, 0});
    vectors.put(chunk(3, 11, "old"), new float[] {0, 0, 1});
    vectors.delete(10L, "old");
    assertEquals(0, vectors.count(10L, "old"));
    assertEquals(1, vectors.count(10L, "new"));
    assertEquals(1, vectors.count(11L, "old"));
    vectors.delete(10L, null);
    vectors.delete(10L, null);
    assertEquals(0, vectors.count(10L, null));
    assertEquals(1, vectors.count(11L, null));
  }

  @Test
  void keywordSubsetReadsOnlyRequestedStoredChunksWithOriginalCosineScores() {
    vectors.put(chunk(1, 10, "a"), new float[] {1, 0, 0});
    vectors.put(chunk(9007199254740993L, 11, "b"), new float[] {0, 1, 0});
    var hits = vectors.searchSubset(new float[] {1, 0, 0}, List.of(9007199254740993L, 99L));
    assertEquals(1, hits.size());
    assertEquals(9007199254740993L, hits.get(0).chunkId());
    assertEquals(0, hits.get(0).score(), .00001);
    vectors.delete(11L, null);
    assertTrue(vectors.searchSubset(new float[] {1, 0, 0}, List.of(9007199254740993L)).isEmpty());
  }
}
