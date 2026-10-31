package edu.campus.rag.service;

import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
  Map<String, Object> upload(MultipartFile file, String title, String category, Long uploader);

  Map<String, Object> detail(Long id);

  Map<String, Object> rebuild(Long id);

  Map<String, Object> patch(Long id, Map<String, String> input);

  Map<String, Object> delete(Long id);

  Map<String, Object> list(
      int page,
      int size,
      String vectorStatus,
      String category,
      String lifecycleStatus,
      String keyword);

  void failure(Long id, String task, String code, String stage, String message);

  void enqueueDelete(Long id);
}
