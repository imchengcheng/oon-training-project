package edu.campus.rag.controller;

import static edu.campus.rag.common.Api.id;
import edu.campus.rag.service.KnowledgeService;
import edu.campus.rag.service.KnowledgeService.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/student/campus")
public class KnowledgeController extends BaseController {
  private final KnowledgeService knowledge;
  public KnowledgeController(KnowledgeService knowledge) { this.knowledge = knowledge; }

  @GetMapping("/documents")
  public ResponseEntity<?> documents(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size,
      @RequestParam(required=false) String category, @RequestParam(required=false) String keyword, HttpServletRequest r) {
    return ok(200, knowledge.documents(user(), page, size, category, keyword), r);
  }
  @GetMapping("/documents/{doc}")
  public ResponseEntity<?> document(@PathVariable String doc, HttpServletRequest r) { return ok(200, knowledge.document(id(doc), user()), r); }
  @GetMapping("/favorites")
  public ResponseEntity<?> favorites(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
      @RequestParam(required=false) String targetType, @RequestParam(required=false) String keyword, HttpServletRequest r) {
    return ok(200, knowledge.favorites(user(), page, size, targetType, keyword), r);
  }
  @PutMapping("/favorites")
  public ResponseEntity<?> favorite(@RequestBody FavoriteInput input, HttpServletRequest r) { return ok(200, knowledge.favorite(user(), input), r); }
  @GetMapping("/favorites/lookup")
  public ResponseEntity<?> lookup(@RequestParam String targetType, @RequestParam String targetId, HttpServletRequest r) {
    return ok(200, knowledge.lookupFavorite(user(), targetType, targetId), r);
  }
  @PatchMapping("/favorites/{favorite}")
  public ResponseEntity<?> note(@PathVariable String favorite, @RequestBody NoteInput input, HttpServletRequest r) {
    return ok(200, knowledge.updateFavorite(id(favorite), user(), input), r);
  }
  @DeleteMapping("/favorites/{favorite}")
  public ResponseEntity<?> remove(@PathVariable String favorite, HttpServletRequest r) { return ok(200, knowledge.deleteFavorite(id(favorite), user()), r); }
  @GetMapping("/sessions")
  public ResponseEntity<?> sessions(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
      @RequestParam(required=false) String status, @RequestParam(required=false) String keyword, HttpServletRequest r) {
    return ok(200, knowledge.sessions(user(), page, size, status, keyword), r);
  }
  @PutMapping("/sessions/{session}")
  public ResponseEntity<?> rename(@PathVariable String session, @RequestBody TitleInput input, HttpServletRequest r) {
    return ok(200, knowledge.renameSession(id(session), user(), input), r);
  }
  @GetMapping("/sessions/{session}/export")
  public ResponseEntity<?> export(@PathVariable String session, HttpServletRequest r) { return ok(200, knowledge.exportSession(id(session), user()), r); }
}
