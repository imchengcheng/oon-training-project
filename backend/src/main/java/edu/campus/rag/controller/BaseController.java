package edu.campus.rag.controller;

import static edu.campus.rag.common.Api.response;

import edu.campus.rag.common.config.SecurityConfig;
import edu.campus.rag.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public abstract class BaseController {
  protected ResponseEntity<ApiResponse<Object>> ok(
      int status, Object data, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .body(response("0", "success", data, (String) request.getAttribute("requestId")));
  }

  protected Long user() {
    return SecurityConfig.current().id();
  }
}
