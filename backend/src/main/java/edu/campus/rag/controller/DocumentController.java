package edu.campus.rag.controller;

import static edu.campus.rag.common.Api.*;

import edu.campus.rag.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class DocumentController extends BaseController {
  private final DocumentService docs;

  public DocumentController(DocumentService docs) {
    this.docs = docs;
  }

  @PostMapping("/admin/documents")
  public ResponseEntity<?> upload(
      @RequestParam MultipartFile file,
      @RequestParam String title,
      @RequestParam String category,
      HttpServletRequest r) {
    require(
        Set.of("file", "title", "category").containsAll(r.getParameterMap().keySet()),
        400,
        "C-0001",
        "上传字段无效");
    return ok(202, docs.upload(file, title, category, user()), r);
  }

  @GetMapping("/admin/documents")
  public ResponseEntity<?> documents(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String vectorStatus,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String lifecycleStatus,
      @RequestParam(required = false) String keyword,
      HttpServletRequest r) {
    return ok(200, docs.list(page, size, vectorStatus, category, lifecycleStatus, keyword), r);
  }

  @GetMapping("/admin/documents/{id}")
  public ResponseEntity<?> document(@PathVariable String id, HttpServletRequest r) {
    return ok(200, docs.detail(id(id)), r);
  }

  @PatchMapping("/admin/documents/{id}")
  public ResponseEntity<?> patch(
      @PathVariable String id, @RequestBody Map<String, String> body, HttpServletRequest r) {
    return ok(200, docs.patch(id(id), body), r);
  }

  @PostMapping("/admin/documents/{id}/revectorize")
  public ResponseEntity<?> rebuild(@PathVariable String id, HttpServletRequest r) {
    return ok(202, docs.rebuild(id(id)), r);
  }

  @DeleteMapping("/admin/documents/{id}")
  public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest r) {
    var d = docs.delete(id(id));
    return ok("DELETED".equals(d.get("lifecycleStatus")) ? 200 : 202, d, r);
  }
}
