package io.archdeck.backend.controller;

import io.archdeck.backend.service.Accounts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
  private final Accounts accounts;

  public AuthController(Accounts a) {
    accounts = a;
  }

  @GetMapping("/api/health")
  Map<String, String> health() {
    return Map.of("status", "ok");
  }

  @GetMapping("/api/auth/csrf")
  Map<String, String> csrf(CsrfToken t) {
    return Map.of("token", t.getToken(), "headerName", t.getHeaderName());
  }

  @GetMapping("/api/auth/me")
  Map<String, Object> me(@AuthenticationPrincipal Accounts.Principal p) {
    return p.view();
  }

  @PostMapping("/api/auth/password")
  void password(
      @AuthenticationPrincipal Accounts.Principal p,
      @RequestBody Map<String, String> b,
      HttpServletRequest q) {
    accounts.change(p, b.getOrDefault("currentPassword", ""), b.getOrDefault("newPassword", ""));
    var s = q.getSession(false);
    if (s != null) s.invalidate();
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @GetMapping("/api/admin/users")
  List<Map<String, Object>> users() {
    return accounts.all();
  }

  @PostMapping("/api/admin/users")
  Map<String, Object> create(@RequestBody Map<String, Object> b) {
    return accounts.create(b);
  }

  @PutMapping("/api/admin/users/{id}")
  Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> b) {
    return accounts.update(id, b);
  }
}
