package edu.campus.rag.controller;

import edu.campus.rag.dto.LoginRequest;
import edu.campus.rag.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController extends BaseController {
  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
    return ok(200, authService.login(body, request.getRemoteAddr()), request);
  }
}
