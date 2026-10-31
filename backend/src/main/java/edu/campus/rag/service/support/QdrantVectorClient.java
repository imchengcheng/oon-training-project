package edu.campus.rag.service.support;

import static edu.campus.rag.common.Api.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.rag.common.Api;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.common.exception.BusinessException;
import edu.campus.rag.entity.Chunk;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Qdrant REST API on port 6333. IDs in payload are strings to preserve MySQL BIGINT values. */
@Component
public class QdrantVectorClient {
  private record Response(int status, JsonNode body) {}

  private final ObjectMapper json;
  private final HttpClient http;
  private final URI base;
  private final String apiKey;
  private final Duration timeout;
  private final int dimension;
  private final String collection;
  private final String path;

  public QdrantVectorClient(AppProperties settings, ObjectMapper json) {
    this.json = json;
    try {
      base = URI.create(settings.qdrantUrl.replaceAll("/+$", ""));
      Api.require(
          ("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
              && base.getHost() != null
              && base.getUserInfo() == null
              && base.getQuery() == null
              && base.getFragment() == null,
          500,
          "C-0003",
          "QDRANT_URL 必须是有效的 HTTP 服务地址");
      Api.require(
          settings.qdrantTimeoutSeconds >= 1 && settings.qdrantTimeoutSeconds <= 60,
          500,
          "C-0003",
          "Qdrant 超时须为 1～60 秒");
      timeout = Duration.ofSeconds(settings.qdrantTimeoutSeconds);
      apiKey = settings.qdrantApiKey == null ? "" : settings.qdrantApiKey;
      dimension = settings.embeddingDimension;
      // Preserve the legacy default local-embedding collection while decoupling chat providers.
      String identity =
          settings.vectorNamespace
              + ":"
              + ("local".equals(settings.effectiveEmbeddingMode()) ? "demo" : "openai")
              + ":"
              + ("local".equals(settings.effectiveEmbeddingMode())
                  ? "https://api.openai.com/v1" : settings.effectiveEmbeddingBaseUrl())
              + ":"
              + settings.embeddingModel
              + ":"
              + dimension;
      String fingerprint =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(StandardCharsets.UTF_8)))
              .substring(0, 16);
      String namespace = settings.vectorNamespace.replaceAll("[^a-zA-Z0-9_-]", "_");
      namespace = namespace.substring(0, Math.min(24, namespace.length()));
      collection = "campus_" + namespace + "_" + fingerprint;
      path = "/collections/" + collection;
      http = HttpClient.newBuilder().connectTimeout(timeout).build();
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw Api.fail(500, "C-0003", "Qdrant 配置无效");
    }
  }

  public String collectionName() {
    return collection;
  }

  /**
   * Check every operation so a removed/replaced collection never silently uses the wrong dimension.
   */
  public synchronized void initialize() {
    Response response = exchange("GET", path, null);
    if (response.status == 404) {
      Response created =
          exchange("PUT", path, map("vectors", map("size", dimension, "distance", "Cosine")));
      // A parallel request or process may have created the same collection.
      if (created.status != 400 && created.status != 409) requireSuccess(created);
      response = exchange("GET", path, null);
    }
    requireSuccess(response);
    var vectors = response.body.path("result").path("config").path("params").path("vectors");
    Api.require(
        vectors.path("size").asInt(-1) == dimension
            && "Cosine".equalsIgnoreCase(vectors.path("distance").asText()),
        503,
        "Q-0004",
        "Qdrant 集合维度或距离配置不匹配，请检查模型配置后重建索引");
  }

  public void put(Chunk chunk, float[] vector) {
    initialize();
    String pointId =
        UUID.nameUUIDFromBytes((collection + ":" + chunk.id).getBytes(StandardCharsets.UTF_8))
            .toString();
    Map<String, Object> point =
        map(
            "id",
            pointId,
            "vector",
            vector,
            "payload",
            map(
                "chunkId",
                chunk.id.toString(),
                "docId",
                chunk.docId.toString(),
                "generation",
                chunk.generation));
    requireCompleted(exchange("PUT", path + "/points?wait=true", map("points", List.of(point))));
  }

  public long count(Long docId, String generation) {
    initialize();
    var response =
        exchange(
            "POST",
            path + "/points/count",
            map("filter", filter(docId, generation), "exact", true));
    requireSuccess(response);
    var count = response.body.path("result").path("count");
    Api.require(
        count.isIntegralNumber() && count.canConvertToLong() && count.asLong() >= 0,
        503,
        "Q-0004",
        "Qdrant 向量数量响应无效");
    return count.asLong();
  }

  public List<VectorStore.Hit> search(float[] vector, int limit) {
    return query(vector, limit, null);
  }

  public List<VectorStore.Hit> searchSubset(float[] vector, List<Long> ids) {
    if (ids.isEmpty()) return List.of();
    return query(vector, ids.size(), map("must", List.of(
        map("key", "chunkId", "match", map("any", ids.stream().map(Object::toString).toList())))));
  }

  private List<VectorStore.Hit> query(float[] vector, int limit, Map<String, Object> filter) {
    initialize();
    var body = map("query", vector, "limit", limit, "with_payload", true,
        "with_vector", false, "params", map("exact", true));
    if (filter != null) body.put("filter", filter);
    var response =
        exchange(
            "POST",
            path + "/points/query",
            body);
    requireSuccess(response);
    var points = response.body.path("result").path("points");
    Api.require(points.isArray(), 503, "Q-0004", "Qdrant 检索响应无效");
    var hits = new ArrayList<VectorStore.Hit>();
    for (var point : points) {
      try {
        Long id = Long.valueOf(point.path("payload").path("chunkId").asText());
        double score = point.path("score").asDouble(Double.NaN);
        Api.require(id > 0 && Double.isFinite(score), 503, "Q-0004", "Qdrant 检索数据无效");
        hits.add(new VectorStore.Hit(id, Math.max(-1, Math.min(1, score))));
      } catch (NumberFormatException ex) {
        throw Api.fail(503, "Q-0004", "Qdrant 分块标识无效");
      }
    }
    return hits;
  }

  public void delete(Long docId, String generation) {
    initialize();
    requireCompleted(
        exchange(
            "POST", path + "/points/delete?wait=true", map("filter", filter(docId, generation))));
  }

  private Map<String, Object> filter(Long docId, String generation) {
    var conditions = new ArrayList<Map<String, Object>>();
    conditions.add(map("key", "docId", "match", map("value", docId.toString())));
    if (generation != null)
      conditions.add(map("key", "generation", "match", map("value", generation)));
    return map("must", conditions);
  }

  private void requireCompleted(Response response) {
    requireSuccess(response);
    Api.require(
        "completed".equals(response.body.path("result").path("status").asText()),
        503,
        "Q-0004",
        "Qdrant 写入或删除尚未完成");
  }

  private void requireSuccess(Response response) {
    Api.require(
        response.status >= 200
            && response.status < 300
            && "ok".equals(response.body.path("status").asText()),
        503,
        "Q-0004",
        "Qdrant 请求失败（HTTP " + response.status + "），请检查服务和配置");
  }

  private Response exchange(String method, String endpoint, Object body) {
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(base.toString() + endpoint))
              .timeout(timeout)
              .header("Accept", "application/json");
      if (!apiKey.isBlank()) builder.header("api-key", apiKey);
      if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
      else
        builder
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
      var response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      // Missing collections may return an empty/non-JSON error body through a reverse proxy.
      JsonNode parsed =
          response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
      return new Response(response.statusCode(), parsed);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw Api.fail(503, "Q-0004", "Qdrant 请求已中断，请重试");
    } catch (IOException | IllegalArgumentException ex) {
      LoggerFactory.getLogger(getClass())
          .debug("Qdrant transport failed: {}", ex.getClass().getSimpleName());
      throw Api.fail(503, "Q-0004", "无法连接 Qdrant 或响应无效，请检查本机 6333 服务");
    }
  }
}
