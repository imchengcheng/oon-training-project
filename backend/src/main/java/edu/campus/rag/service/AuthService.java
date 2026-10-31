package edu.campus.rag.service;

import edu.campus.rag.dto.LoginRequest;
import java.util.Map;

public interface AuthService {
  Map<String, Object> login(LoginRequest request, String remoteAddress);
}
