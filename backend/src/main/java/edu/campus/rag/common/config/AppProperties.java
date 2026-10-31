package edu.campus.rag.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
  public String jwtSecret, storage, corsOrigin, adminPassword, studentPassword;
  public String vectorStore,
      vectorNamespace,
      modelMode,
      modelBaseUrl,
      modelApiKey,
      chatModel,
      embeddingModel;
  public boolean seed;
  public String qdrantUrl = "http://127.0.0.1:6333";
  public String qdrantApiKey = "";
  public int qdrantTimeoutSeconds = 5;
  public String embeddingMode = "auto";
  public String embeddingBaseUrl = "";
  public String embeddingApiKey = "";
  public String retrievalMode = "vector";
  public int modelTimeoutSeconds = 25;

  public String effectiveEmbeddingMode() {
    return "auto".equals(embeddingMode)
        ? ("openai".equals(modelMode) ? "openai" : "local")
        : embeddingMode;
  }

  public String effectiveEmbeddingBaseUrl() {
    return embeddingBaseUrl == null || embeddingBaseUrl.isBlank() ? modelBaseUrl : embeddingBaseUrl;
  }

  public String effectiveEmbeddingApiKey() {
    return embeddingApiKey == null || embeddingApiKey.isBlank() ? modelApiKey : embeddingApiKey;
  }

  public String getEmbeddingMode() {
    return embeddingMode;
  }

  public void setEmbeddingMode(String value) {
    embeddingMode = value;
  }

  public String getEmbeddingBaseUrl() {
    return embeddingBaseUrl;
  }

  public void setEmbeddingBaseUrl(String value) {
    embeddingBaseUrl = value;
  }

  public String getEmbeddingApiKey() {
    return embeddingApiKey;
  }

  public void setEmbeddingApiKey(String value) {
    embeddingApiKey = value;
  }

  public String getRetrievalMode() {
    return retrievalMode;
  }

  public void setRetrievalMode(String value) {
    retrievalMode = value;
  }

  public int getModelTimeoutSeconds() {
    return modelTimeoutSeconds;
  }

  public void setModelTimeoutSeconds(int value) {
    modelTimeoutSeconds = value;
  }


  public String getQdrantUrl() {
    return qdrantUrl;
  }

  public void setQdrantUrl(String v) {
    qdrantUrl = v;
  }

  public String getQdrantApiKey() {
    return qdrantApiKey;
  }

  public void setQdrantApiKey(String v) {
    qdrantApiKey = v;
  }

  public int getQdrantTimeoutSeconds() {
    return qdrantTimeoutSeconds;
  }

  public void setQdrantTimeoutSeconds(int v) {
    qdrantTimeoutSeconds = v;
  }

  public int tokenSeconds,
      embeddingDimension,
      topK,
      modelContextTokens,
      questionTimeoutSeconds,
      taskTimeoutSeconds,
      loginLimit,
      questionLimit;
  public double similarityThreshold;

  public String getJwtSecret() {
    return jwtSecret;
  }

  public void setJwtSecret(String v) {
    jwtSecret = v;
  }

  public String getStorage() {
    return storage;
  }

  public void setStorage(String v) {
    storage = v;
  }

  public String getCorsOrigin() {
    return corsOrigin;
  }

  public void setCorsOrigin(String v) {
    corsOrigin = v;
  }

  public String getAdminPassword() {
    return adminPassword;
  }

  public void setAdminPassword(String v) {
    adminPassword = v;
  }

  public String getStudentPassword() {
    return studentPassword;
  }

  public void setStudentPassword(String v) {
    studentPassword = v;
  }

  public String getVectorStore() {
    return vectorStore;
  }

  public void setVectorStore(String v) {
    vectorStore = v;
  }

  public String getVectorNamespace() {
    return vectorNamespace;
  }

  public void setVectorNamespace(String v) {
    vectorNamespace = v;
  }

  public String getModelMode() {
    return modelMode;
  }

  public void setModelMode(String v) {
    modelMode = v;
  }

  public String getModelBaseUrl() {
    return modelBaseUrl;
  }

  public void setModelBaseUrl(String v) {
    modelBaseUrl = v;
  }

  public String getModelApiKey() {
    return modelApiKey;
  }

  public void setModelApiKey(String v) {
    modelApiKey = v;
  }

  public String getChatModel() {
    return chatModel;
  }

  public void setChatModel(String v) {
    chatModel = v;
  }

  public String getEmbeddingModel() {
    return embeddingModel;
  }

  public void setEmbeddingModel(String v) {
    embeddingModel = v;
  }

  public boolean isSeed() {
    return seed;
  }

  public void setSeed(boolean v) {
    seed = v;
  }

  public int getTokenSeconds() {
    return tokenSeconds;
  }

  public void setTokenSeconds(int v) {
    tokenSeconds = v;
  }

  public int getEmbeddingDimension() {
    return embeddingDimension;
  }

  public void setEmbeddingDimension(int v) {
    embeddingDimension = v;
  }

  public int getTopK() {
    return topK;
  }

  public void setTopK(int v) {
    topK = v;
  }

  public int getModelContextTokens() {
    return modelContextTokens;
  }

  public void setModelContextTokens(int v) {
    modelContextTokens = v;
  }

  public int getQuestionTimeoutSeconds() {
    return questionTimeoutSeconds;
  }

  public void setQuestionTimeoutSeconds(int v) {
    questionTimeoutSeconds = v;
  }

  public int getTaskTimeoutSeconds() {
    return taskTimeoutSeconds;
  }

  public void setTaskTimeoutSeconds(int v) {
    taskTimeoutSeconds = v;
  }

  public int getLoginLimit() {
    return loginLimit;
  }

  public void setLoginLimit(int v) {
    loginLimit = v;
  }

  public int getQuestionLimit() {
    return questionLimit;
  }

  public void setQuestionLimit(int v) {
    questionLimit = v;
  }

  public double getSimilarityThreshold() {
    return similarityThreshold;
  }

  public void setSimilarityThreshold(double v) {
    similarityThreshold = v;
  }
}
