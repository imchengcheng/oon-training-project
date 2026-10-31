package edu.campus.rag.service.support;

import edu.campus.rag.common.Api;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.entity.Chunk;
import edu.campus.rag.mapper.DataMapper;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class HybridRetriever {
  private final DataMapper db;
  private final VectorStore vectors;
  private final AppProperties settings;

  public HybridRetriever(DataMapper db, VectorStore vectors, AppProperties settings) {
    this.db = db;
    this.vectors = vectors;
    this.settings = settings;
    Api.require(Set.of("vector", "hybrid").contains(settings.retrievalMode),
        500, "C-0003", "RETRIEVAL_MODE 仅支持 vector / hybrid");
  }

  public List<ModelGateway.Evidence> merge(String query, float[] embedding,
      List<ModelGateway.Evidence> semantic) {
    if (!"hybrid".equals(settings.retrievalMode)) return semantic;
    var corpus = db.tx(() -> db.list(Chunk.class,
        "select c from Chunk c, Document d where c.docId=d.id and d.lifecycleStatus='ACTIVE'"
            + " and c.generation=d.activeGeneration and c.isDeleted=false"
            + " and c.embeddingModel=?1 and c.embeddingDimension=?2 order by c.id",
        settings.embeddingModel, settings.embeddingDimension));
    var keywords = KeywordRanker.search(query, corpus, settings.topK * 4);
    if (keywords.isEmpty()) return semantic;
    var byId = new HashMap<Long, Chunk>();
    keywords.forEach(h -> byId.put(h.chunk().id, h.chunk()));
    // Read actual cosine scores from the stored vectors, keeping citation scores meaningful.
    // Missing vectors cannot enter the evidence set solely through the SQL keyword branch.
    var stored = vectors.searchSubset(embedding, new ArrayList<>(byId.keySet()));
    var available = new HashMap<Long, Double>();
    stored.forEach(h -> available.put(h.chunkId(), h.score()));
    var lexical = keywords.stream().filter(h -> available.containsKey(h.chunk().id))
        .map(h -> new ModelGateway.Evidence(h.chunk(), available.get(h.chunk().id))).toList();
    return fuse(semantic, lexical);
  }

  public static List<ModelGateway.Evidence> fuse(List<ModelGateway.Evidence> semantic,
      List<ModelGateway.Evidence> lexical) {
    var evidence = new HashMap<Long, ModelGateway.Evidence>();
    var ranks = new HashMap<Long, Double>();
    for (var branch : List.of(semantic, lexical)) {
      var seen = new HashSet<Long>();
      int rank = 0;
      for (var item : branch) {
        if (!seen.add(item.chunk().id)) continue;
        evidence.putIfAbsent(item.chunk().id, item);
        ranks.merge(item.chunk().id, 1.0 / (60 + ++rank), Double::sum);
      }
    }
    return evidence.values().stream().sorted(
        Comparator.<ModelGateway.Evidence>comparingDouble(e -> ranks.get(e.chunk().id)).reversed()
            .thenComparing(e -> e.chunk().id)).toList();
  }
}
