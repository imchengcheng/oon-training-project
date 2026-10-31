package edu.campus.rag;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.entity.Chunk;
import edu.campus.rag.service.support.ModelGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in only; sends synthetic evidence, never business documents. */
@EnabledIfEnvironmentVariable(named = "RUN_DEEPSEEK_LIVE", matches = "true")
class DeepSeekLiveIT {
  @Test
  void officialFlashSupportsEvidenceJsonAndFollowUpRewrite() {
    var s = new AppProperties();
    s.modelMode = "deepseek";
    s.modelBaseUrl = System.getenv().getOrDefault("MODEL_BASE_URL", "https://api.deepseek.com");
    s.modelApiKey = System.getenv("MODEL_API_KEY");
    s.chatModel = System.getenv().getOrDefault("CHAT_MODEL", "deepseek-flash");
    s.embeddingMode = "local";
    s.embeddingModel = "demo-character-bigram-v1";
    s.embeddingDimension = 256;
    s.topK = 5;
    s.similarityThreshold = .13;
    s.modelContextTokens = 16384;
    var gateway = new ModelGateway(s, new ObjectMapper());
    var c = new Chunk();
    c.chunkText = "第一条（测试示例）补办学生证需要提交补办申请表和一寸照片。";
    var answer = gateway.generate("补办学生证需要提交哪些材料？",
        List.of(new ModelGateway.Evidence(c, .8)));
    assertTrue(answer.sufficient());
    assertTrue(answer.answer().contains("[1]"));
    assertTrue(answer.answer().contains("申请表"));
    String rewritten = gateway.rewrite("那照片呢？", List.of("学生：补办学生证需要提交哪些材料？\n助手：申请表和一寸照片。"));
    assertTrue(rewritten.contains("学生证"));
    assertTrue(rewritten.contains("照片"));
  }
}
