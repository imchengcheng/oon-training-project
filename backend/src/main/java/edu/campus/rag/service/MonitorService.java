package edu.campus.rag.service;

import java.util.Map;

public interface MonitorService {
  Map<String, Object> summary();

  Map<String, Object> quality(String from, String to);

  Map<String, Object> feedback(int page, int size, String rating, String from, String to);
}
