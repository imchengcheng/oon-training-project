package edu.campus.rag;

import static org.junit.jupiter.api.Assertions.*;
import edu.campus.rag.entity.Chunk;
import edu.campus.rag.service.support.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridRetrieverTest {
  Chunk chunk(long id, String text) {
    var c = new Chunk();
    c.id = id;
    c.chunkText = text;
    return c;
  }

  @Test
  void chineseTermsAndExactNumbersRankRelevantClauseAndRejectUnrelatedQueries() {
    var a = chunk(1, "第一条 宿舍内禁止使用明火和大功率电器。");
    var b = chunk(2, "第二条 学生申请补办学生证，应提交申请表和一寸照片，工本费20元。");
    var corpus = List.of(a, b);
    assertEquals(2L, KeywordRanker.search("补办学生证照片", corpus, 2).get(0).chunk().id);
    assertEquals(2L, KeywordRanker.search("20", corpus, 2).get(0).chunk().id);
    assertTrue(KeywordRanker.search("番茄炒蛋", corpus, 2).isEmpty());
    assertTrue(KeywordRanker.search("？", corpus, 2).isEmpty());
  }

  @Test
  void reciprocalRankFusionPromotesSharedEvidenceDeduplicatesAndPreservesCosine() {
    var a = new ModelGateway.Evidence(chunk(1, "向量结果"), .9);
    var b = new ModelGateway.Evidence(chunk(2, "共同结果"), .6);
    var c = new ModelGateway.Evidence(chunk(3, "关键词结果"), -.1);
    var result = HybridRetriever.fuse(List.of(a, b), List.of(c, b, b));
    assertEquals(List.of(2L, 1L, 3L), result.stream().map(e -> e.chunk().id).toList());
    assertEquals(.6, result.get(0).score());
    assertEquals(-.1, result.get(2).score());
  }
}
