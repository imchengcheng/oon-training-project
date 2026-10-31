package edu.campus.rag.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.rag.common.Api;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.common.exception.BusinessException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** DeepSeek's native thinking toggle is not exposed by the pinned LangChain4j version. */
public class DeepSeekChatClient {
  private final HttpClient http;
  private final URI endpoint;
  private final String apiKey;
  private final String model;
  private final Duration timeout;
  private final ObjectMapper json;

  public DeepSeekChatClient(AppProperties settings, ObjectMapper json) {
    this.json = json;
    this.apiKey = settings.modelApiKey;
    this.model = settings.chatModel;
    timeout = Duration.ofSeconds(settings.modelTimeoutSeconds);
    try {
      var base = URI.create(settings.modelBaseUrl.replaceAll("/+$", ""));
      Api.require(
          Set.of("http", "https").contains(base.getScheme()) && base.getHost() != null
              && base.getUserInfo() == null && base.getQuery() == null && base.getFragment() == null,
          500, "C-0003", "模型地址须为有效 HTTP 服务地址");
      endpoint = URI.create(base + "/chat/completions");
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw Api.fail(500, "C-0003", "模型地址无效");
    }
    http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  public String complete(String system, String user, boolean structured) {
    var body = new LinkedHashMap<String, Object>();
    body.put("model", model);
    body.put("messages", List.of(Map.of("role", "system", "content", system),
        Map.of("role", "user", "content", user)));
    body.put("thinking", Map.of("type", "disabled"));
    body.put("temperature", 0);
    body.put("max_tokens", structured ? 2048 : 512);
    body.put("stream", false);
    if (structured) body.put("response_format", Map.of("type", "json_object"));
    try {
      var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
          .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      // Provider response bodies may contain private data: never log or forward them.
      switch (response.statusCode()) {
        case 200 -> { }
        case 401, 403 -> throw Api.fail(502, "Q-0005", "模型服务认证失败，请检查密钥或访问权限");
        case 402 -> throw Api.fail(502, "Q-0005", "模型服务余额不足，请检查账户余额");
        case 429 -> throw Api.fail(503, "Q-0005", "模型服务请求受限，请稍后重试");
        default -> throw Api.fail(502, "Q-0005", "模型服务请求失败，请检查模型配置或稍后重试");
      }
      var root = json.readTree(response.body());
      var choice = root.path("choices").path(0);
      Api.require("stop".equals(choice.path("finish_reason").asText()),
          502, "Q-0010", "模型输出未完整结束，请缩小问题范围后重试");
      var content = choice.path("message").path("content");
      Api.require(content.isTextual() && !content.asText().isBlank(),
          502, "Q-0010", "模型未返回有效回答");
      return content.asText();
    } catch (BusinessException ex) {
      throw ex;
    } catch (java.net.http.HttpTimeoutException ex) {
      throw Api.fail(504, "Q-0009", "模型服务响应超时，请稍后重试");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw Api.fail(504, "Q-0009", "模型请求已取消或超时");
    } catch (Exception ex) {
      throw Api.fail(502, "Q-0005", "模型服务连接失败或响应格式无效");
    }
  }
}
