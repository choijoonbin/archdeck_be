package io.archdeck.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.archdeck.backend.service.Accounts;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new DelegatingPasswordEncoder(
        "argon2", Map.of("argon2", new Argon2PasswordEncoder(16, 32, 1, 19456, 2)));
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, Accounts accounts, ObjectMapper json, PasswordEncoder encoder)
      throws Exception {
    var provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(accounts);
    provider.setPasswordEncoder(encoder);
    var csrf = new HttpSessionCsrfTokenRepository();
    csrf.setHeaderName("X-CSRF-TOKEN");
    http.authenticationProvider(provider)
        .csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/health", "/error")
                    .permitAll()
                    .requestMatchers("/api/admin/**", "/api/research/**")
                    .hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/**")
                    .authenticated()
                    .requestMatchers("/api/auth/password", "/api/auth/logout")
                    .authenticated()
                    .anyRequest()
                    .hasRole("ADMIN"))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> {
                          r.setStatus(401);
                          r.setContentType("application/json");
                          r.getWriter().write("{\"error\":\"authentication_required\"}");
                        })
                    .accessDeniedHandler(
                        (q, r, x) -> {
                          r.setStatus(403);
                          r.setContentType("application/json");
                          r.getWriter().write("{\"error\":\"access_denied\"}");
                        }))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/auth/login")
                    .successHandler(
                        (q, r, a) -> {
                          q.getSession()
                              .setAttribute("authenticatedAt", System.currentTimeMillis());
                          r.setContentType("application/json");
                          json.writeValue(
                              r.getOutputStream(), ((Accounts.Principal) a.getPrincipal()).view());
                        })
                    .failureHandler(
                        (q, r, x) -> {
                          r.setStatus(401);
                          r.setContentType("application/json");
                          r.getWriter().write("{\"error\":\"invalid_credentials\"}");
                        }))
        .logout(
            l ->
                l.logoutUrl("/api/auth/logout")
                    .deleteCookies("ArchDeckSession", "__Host-ArchDeckSession")
                    .logoutSuccessHandler((q, r, a) -> r.setStatus(204)))
        .headers(h -> h.cacheControl(c -> {}));
    http.addFilterBefore(
        new LoginThrottle(),
        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(
        new OncePerRequestFilter() {
          protected void doFilterInternal(
              HttpServletRequest q, HttpServletResponse r, FilterChain chain)
              throws ServletException, IOException {
            var a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.getPrincipal() instanceof Accounts.Principal p) {
              var fresh = accounts.byId(p.id());
              var session = q.getSession(false);
              Object since = session == null ? null : session.getAttribute("authenticatedAt");
              if (fresh == null
                  || !fresh.enabled()
                  || fresh.authVersion() != p.authVersion()
                  || (since instanceof Long t && System.currentTimeMillis() - t > 28800000)) {
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                r.setStatus(401);
                return;
              }
              if (fresh.mustChangePassword()
                  && !Set.of(
                          "/api/auth/me",
                          "/api/auth/csrf",
                          "/api/auth/password",
                          "/api/auth/logout")
                      .contains(q.getRequestURI())) {
                r.setStatus(403);
                r.setContentType("application/json");
                r.getWriter().write("{\"error\":\"password_change_required\"}");
                return;
              }
            }
            chain.doFilter(q, r);
          }
        },
        org.springframework.security.web.context.SecurityContextHolderFilter.class);
    return http.build();
  }

  static class LoginThrottle extends OncePerRequestFilter {
    record Window(long start, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    synchronized boolean allow(String key, long now, int limit) {
      if (windows.size() >= 10000) {
        windows.entrySet().removeIf(e -> now - e.getValue().start() > 900000);
        if (windows.size() >= 10000 && !windows.containsKey(key)) return false;
      }
      Window v = windows.get(key);
      if (v == null || now - v.start() > 900000) v = new Window(now, 0);
      v = new Window(v.start(), v.count() + 1);
      windows.put(key, v);
      return v.count() <= limit;
    }

    protected void doFilterInternal(HttpServletRequest q, HttpServletResponse r, FilterChain c)
        throws ServletException, IOException {
      if (q.getRequestURI().equals("/api/auth/login") && q.getMethod().equals("POST")) {
        long now = System.currentTimeMillis();
        String username = Objects.toString(q.getParameter("username"), "");
        if (username.length() > 32
            || !allow("ip:" + q.getRemoteAddr(), now, 100)
            || !allow("user:" + username.toLowerCase(Locale.ROOT), now, 20)) {
          r.setStatus(429);
          r.setHeader("Retry-After", "900");
          return;
        }
      }
      c.doFilter(q, r);
    }
  }
}
