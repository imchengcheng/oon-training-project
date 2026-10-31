package edu.campus.rag.controller;

import static edu.campus.rag.common.Api.*;

import edu.campus.rag.common.RateLimits;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.dto.FeedbackRequest;
import edu.campus.rag.dto.QuestionRequest;
import edu.campus.rag.service.QuestionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class QuestionController extends BaseController {
  private final QuestionService qa;
  private final AppProperties properties;
  private final RateLimits limits;

  public QuestionController(QuestionService qa, AppProperties properties, RateLimits limits) {
    this.qa = qa;
    this.properties = properties;
    this.limits = limits;
  }

  @PostMapping("/qa/sessions")
  public ResponseEntity<?> create(
      @RequestBody(required = false) Map<String, Object> body, HttpServletRequest r) {
    return ok(201, qa.create(user(), body), r);
  }

  @GetMapping("/qa/sessions")
  public ResponseEntity<?> sessions(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String status,
      HttpServletRequest r) {
    return ok(200, qa.sessions(user(), page, size, status), r);
  }

  @GetMapping("/qa/sessions/{id}")
  public ResponseEntity<?> session(
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest r) {
    return ok(200, qa.detail(id(id), user(), page, size), r);
  }

  @PutMapping("/qa/sessions/{id}/close")
  public ResponseEntity<?> close(@PathVariable String id, HttpServletRequest r) {
    return ok(200, qa.close(id(id), user()), r);
  }

  @PostMapping("/qa/sessions/{id}/questions")
  public ResponseEntity<?> ask(
      @PathVariable String id, @RequestBody QuestionRequest body, HttpServletRequest r) {
    limits.take("qa:" + user(), properties.questionLimit);
    return ok(200, qa.ask(id(id), user(), body.clientRequestId(), body.content()), r);
  }

  @PutMapping("/qa/messages/{id}/feedback")
  public ResponseEntity<?> feedback(
      @PathVariable String id, @RequestBody FeedbackRequest body, HttpServletRequest r) {
    return ok(200, qa.feedback(id(id), user(), body.rating(), body.remark()), r);
  }

  @GetMapping("/qa/messages/{id}/sources/{source}")
  public ResponseEntity<?> source(
      @PathVariable String id, @PathVariable String source, HttpServletRequest r) {
    return ok(200, qa.source(id(id), id(source), user()), r);
  }
}
