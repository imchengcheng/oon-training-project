package edu.campus.rag.controller;

import static edu.campus.rag.common.Api.*;

import edu.campus.rag.service.MonitorService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MonitorController extends BaseController {
  private final MonitorService monitor;

  public MonitorController(MonitorService monitor) {
    this.monitor = monitor;
  }

  @GetMapping("/admin/monitor/summary")
  public ResponseEntity<?> summary(HttpServletRequest r) {
    return ok(200, monitor.summary(), r);
  }

  @GetMapping("/admin/monitor/qa")
  public ResponseEntity<?> quality(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      HttpServletRequest r) {
    return ok(200, monitor.quality(from, to), r);
  }

  @GetMapping("/admin/feedback")
  public ResponseEntity<?> feedbacks(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String rating,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      HttpServletRequest r) {
    return ok(200, monitor.feedback(page, size, rating, from, to), r);
  }
}
