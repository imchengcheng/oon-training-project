package edu.campus.rag.controller;

import static edu.campus.rag.common.Api.*;

import edu.campus.rag.service.CampusService;
import edu.campus.rag.service.CampusService.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CampusController extends BaseController {
  private final CampusService campus;

  public CampusController(CampusService campus) { this.campus = campus; }

  private static boolean administrator(HttpServletRequest request) {
    return request.getRequestURI().startsWith(request.getContextPath() + "/api/admin/");
  }

  @GetMapping({"/student/campus/guides", "/admin/campus/guides"})
  public ResponseEntity<?> guides(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String category, @RequestParam(required = false) Boolean published,
      HttpServletRequest r) {
    return ok(200, campus.guides(administrator(r), page, size, keyword, category, published), r);
  }

  @GetMapping({"/student/campus/guides/{id}", "/admin/campus/guides/{id}"})
  public ResponseEntity<?> guide(@PathVariable String id, HttpServletRequest r) {
    return ok(200, campus.guide(id(id), administrator(r)), r);
  }

  @PostMapping("/admin/campus/guides")
  public ResponseEntity<?> createGuide(@RequestBody GuideInput body, HttpServletRequest r) {
    return ok(201, campus.saveGuide(null, body), r);
  }

  @PutMapping("/admin/campus/guides/{id}")
  public ResponseEntity<?> updateGuide(@PathVariable String id, @RequestBody GuideInput body, HttpServletRequest r) {
    return ok(200, campus.saveGuide(id(id), body), r);
  }

  @GetMapping({"/student/campus/faqs", "/admin/campus/faqs"})
  public ResponseEntity<?> faqs(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String category, @RequestParam(required = false) Boolean published,
      HttpServletRequest r) {
    return ok(200, campus.faqs(administrator(r), page, size, keyword, category, published), r);
  }

  @GetMapping({"/student/campus/faqs/{id}", "/admin/campus/faqs/{id}"})
  public ResponseEntity<?> faq(@PathVariable String id, HttpServletRequest r) {
    return ok(200, campus.faq(id(id), administrator(r)), r);
  }

  @PostMapping("/admin/campus/faqs")
  public ResponseEntity<?> createFaq(@RequestBody FaqInput body, HttpServletRequest r) {
    return ok(201, campus.saveFaq(null, body), r);
  }

  @PutMapping("/admin/campus/faqs/{id}")
  public ResponseEntity<?> updateFaq(@PathVariable String id, @RequestBody FaqInput body, HttpServletRequest r) {
    return ok(200, campus.saveFaq(id(id), body), r);
  }

  @PostMapping("/student/campus/faqs/{id}/use")
  public ResponseEntity<?> useFaq(@PathVariable String id, HttpServletRequest r) {
    return ok(200, campus.useFaq(id(id)), r);
  }

  @GetMapping({"/student/campus/tickets", "/admin/campus/tickets"})
  public ResponseEntity<?> tickets(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String category, @RequestParam(required = false) String status,
      HttpServletRequest r) {
    return ok(200, campus.tickets(administrator(r), page, size, keyword, category, status), r);
  }

  @GetMapping({"/student/campus/tickets/{id}", "/admin/campus/tickets/{id}"})
  public ResponseEntity<?> ticket(@PathVariable String id, HttpServletRequest r) {
    return ok(200, campus.ticket(id(id), administrator(r)), r);
  }

  @PostMapping("/student/campus/tickets")
  public ResponseEntity<?> createTicket(@RequestBody TicketInput body, HttpServletRequest r) {
    return ok(201, campus.createTicket(body), r);
  }

  @PutMapping("/admin/campus/tickets/{id}")
  public ResponseEntity<?> updateTicket(@PathVariable String id, @RequestBody TicketUpdate body, HttpServletRequest r) {
    return ok(200, campus.updateTicket(id(id), body), r);
  }

  @GetMapping("/admin/campus/users")
  public ResponseEntity<?> users(@RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String roleType, HttpServletRequest r) {
    return ok(200, campus.users(page, size, keyword, roleType), r);
  }

  @PostMapping("/admin/campus/users")
  public ResponseEntity<?> createUser(@RequestBody UserInput body, HttpServletRequest r) {
    return ok(201, campus.createUser(body), r);
  }

  @PutMapping("/admin/campus/users/{id}")
  public ResponseEntity<?> updateUser(@PathVariable String id, @RequestBody UserUpdate body, HttpServletRequest r) {
    return ok(200, campus.updateUser(id(id), body), r);
  }

  @PutMapping("/admin/campus/users/{id}/password")
  public ResponseEntity<?> resetPassword(@PathVariable String id, @RequestBody PasswordInput body, HttpServletRequest r) {
    return ok(200, campus.resetPassword(id(id), body), r);
  }

  @GetMapping("/admin/campus/gaps")
  public ResponseEntity<?> gaps(@RequestParam(required = false) String keyword, HttpServletRequest r) {
    return ok(200, campus.gaps(keyword), r);
  }
}
