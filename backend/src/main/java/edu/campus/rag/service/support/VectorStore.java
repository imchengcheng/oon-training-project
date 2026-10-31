package edu.campus.rag.service.support;

import edu.campus.rag.common.Api;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.entity.Chunk;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Shared vector operations; business services retain their transaction and generation checks. */
@Component
public class VectorStore {
  public record Hit(Long chunkId, double score) {}

  private record Entry(Long docId, String generation, float[] vector) {}

  private final ConcurrentHashMap<Long, Entry> memory = new ConcurrentHashMap<>();
  private final AppProperties settings;
  private final QdrantVectorClient qdrant;

  public VectorStore(AppProperties settings, QdrantVectorClient qdrant) {
    this.settings = settings;
    this.qdrant = qdrant;
    Api.require(
        Set.of("memory", "qdrant").contains(settings.vectorStore),
        500,
        "C-0003",
        "VECTOR_STORE 仅支持 qdrant 或 memory");
  }

  public void initialize() {
    if (!isMemory()) qdrant.initialize();
  }

  public String description() {
    return isMemory() ? "memory" : "qdrant collection=" + qdrant.collectionName();
  }

  private boolean isMemory() {
    return "memory".equals(settings.vectorStore);
  }

  private void validate(float[] vector) {
    Api.require(
        vector != null && vector.length == settings.embeddingDimension, 502, "Q-0006", "嵌入向量维度不匹配");
    for (float value : vector) {
      Api.require(Float.isFinite(value), 502, "Q-0006", "嵌入向量含无效数值");
    }
  }

  public void put(Chunk chunk, float[] vector) {
    validate(vector);
    if (isMemory()) memory.put(chunk.id, new Entry(chunk.docId, chunk.generation, vector.clone()));
    else qdrant.put(chunk, vector);
  }

  public long count(Long docId, String generation) {
    if (!isMemory()) return qdrant.count(docId, generation);
    return memory.values().stream()
        .filter(
            e -> e.docId.equals(docId) && (generation == null || e.generation.equals(generation)))
        .count();
  }

  public List<Hit> search(float[] vector, int limit) {
    validate(vector);
    Api.require(limit > 0, 500, "C-0003", "向量检索数量无效");
    if (!isMemory()) return qdrant.search(vector, limit);
    return memory.entrySet().stream()
        .map(e -> new Hit(e.getKey(), cosine(vector, e.getValue().vector)))
        .sorted(Comparator.comparingDouble(Hit::score).reversed())
        .limit(limit)
        .toList();
  }

  public List<Hit> searchSubset(float[] vector, List<Long> chunkIds) {
    validate(vector);
    if (chunkIds.isEmpty()) return List.of();
    if (!isMemory()) return qdrant.searchSubset(vector, chunkIds);
    var ids = Set.copyOf(chunkIds);
    return memory.entrySet().stream().filter(e -> ids.contains(e.getKey()))
        .map(e -> new Hit(e.getKey(), cosine(vector, e.getValue().vector)))
        .sorted(Comparator.comparingDouble(Hit::score).reversed()).toList();
  }

  private static double cosine(float[] a, float[] b) {
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    return normA == 0 || normB == 0 ? 0 : Math.max(-1, Math.min(1, dot / Math.sqrt(normA * normB)));
  }

  public void delete(Long docId, String generation) {
    if (!isMemory()) qdrant.delete(docId, generation);
    else
      memory
          .entrySet()
          .removeIf(
              e ->
                  e.getValue().docId.equals(docId)
                      && (generation == null || e.getValue().generation.equals(generation)));
  }
}
