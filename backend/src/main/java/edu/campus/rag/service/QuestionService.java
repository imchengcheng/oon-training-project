package edu.campus.rag.service;

import java.util.Map;

public interface QuestionService {
  Map<String, Object> create(Long user, Map<String, Object> body);

  Map<String, Object> sessions(Long user, int page, int size, String status);

  Map<String, Object> detail(Long id, Long user, int page, int size);

  Map<String, Object> close(Long id, Long user);

  Map<String, Object> ask(Long sessionId, Long user, String requestId, String rawContent);

  Map<String, Object> source(Long messageId, Long sourceId, Long user);

  Map<String, Object> feedback(Long messageId, Long user, String rating, String remark);

  void failMessage(Long id, String code, long duration);
}
