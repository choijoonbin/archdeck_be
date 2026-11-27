package io.archdeck.backend.service;

import static org.springframework.http.HttpStatus.*;

import java.io.Serializable;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Accounts implements UserDetailsService {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;

  public Accounts(JdbcTemplate db, PasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  public static final class Principal
      implements UserDetails, Serializable, org.springframework.security.core.CredentialsContainer {
    private final String id, username, displayName, role;
    private transient String hash;
    private final boolean enabled, mustChangePassword;
    private final long authVersion;

    public Principal(
        String id,
        String username,
        String displayName,
        String role,
        String hash,
        boolean enabled,
        boolean mustChangePassword,
        long authVersion) {
      this.id = id;
      this.username = username;
      this.displayName = displayName;
      this.role = role;
      this.hash = hash;
      this.enabled = enabled;
      this.mustChangePassword = mustChangePassword;
      this.authVersion = authVersion;
    }

    public String id() {
      return id;
    }

    public String username() {
      return username;
    }

    public String displayName() {
      return displayName;
    }

    public String role() {
      return role;
    }

    public String hash() {
      return hash;
    }

    public boolean enabled() {
      return enabled;
    }

    public boolean mustChangePassword() {
      return mustChangePassword;
    }

    public long authVersion() {
      return authVersion;
    }

    public Collection<SimpleGrantedAuthority> getAuthorities() {
      return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    public String getPassword() {
      return hash;
    }

    public void eraseCredentials() {
      hash = null;
    }

    public String getUsername() {
      return id;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public Map<String, Object> view() {
      return Map.of(
          "id",
          id,
          "username",
          username,
          "displayName",
          displayName,
          "role",
          role,
          "mustChangePassword",
          mustChangePassword);
    }
  }

  private Principal row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Principal(
        r.getString("id"),
        r.getString("username"),
        r.getString("display_name"),
        r.getString("role"),
        r.getString("password_hash"),
        r.getBoolean("enabled"),
        r.getBoolean("must_change_password"),
        r.getLong("auth_version"));
  }

  public UserDetails loadUserByUsername(String username) {
    return db
        .query(
            "select * from archdeck.app_users where username=?",
            this::row,
            username.trim().toLowerCase(Locale.ROOT))
        .stream()
        .findFirst()
        .orElseThrow(() -> new UsernameNotFoundException("invalid_credentials"));
  }

  public Principal byId(String id) {
    return db.query("select * from archdeck.app_users where id=?::uuid", this::row, id).stream()
        .findFirst()
        .orElse(null);
  }

  public List<Map<String, Object>> all() {
    return db.query("select * from archdeck.app_users order by username", this::row).stream()
        .map(
            p -> {
              var m = new LinkedHashMap<>(p.view());
              m.put("enabled", p.enabled());
              return (Map<String, Object>) m;
            })
        .toList();
  }

  static String required(Map<String, Object> b, String k) {
    String v = Objects.toString(b.get(k), "").trim();
    if (v.isBlank()) throw new ResponseStatusException(BAD_REQUEST, k + "_required");
    return v;
  }

  public static void passwordPolicy(String password) {
    int count = password.codePointCount(0, password.length());
    if (count < 15
        || count > 128
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512)
      throw new ResponseStatusException(BAD_REQUEST, "password_must_be_15_to_128_characters");
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> b) {
    String username = required(b, "username").toLowerCase(Locale.ROOT);
    String password = Objects.toString(b.get("password"), "");
    passwordPolicy(password);
    if (!username.matches("[a-z0-9][a-z0-9._-]{2,31}"))
      throw new ResponseStatusException(BAD_REQUEST, "invalid_username");
    String role = Objects.toString(b.get("role"), "READER");
    if (!List.of("ADMIN", "READER").contains(role))
      throw new ResponseStatusException(BAD_REQUEST, "invalid_role");
    String id = UUID.randomUUID().toString();
    db.update(
        "insert into"
            + " archdeck.app_users(id,username,password_hash,display_name,role)values(?::uuid,?,?,?,?)",
        id,
        username,
        encoder.encode(password),
        required(b, "displayName"),
        role);
    return byId(id).view();
  }

  @Transactional
  public Map<String, Object> update(String id, Map<String, Object> b) {
    db.queryForList("select id from archdeck.app_users where role='ADMIN' and enabled for update");
    Principal p = byId(id);
    if (p == null) throw new ResponseStatusException(NOT_FOUND);
    String role = Objects.toString(b.getOrDefault("role", p.role()));
    boolean enabled = (Boolean) b.getOrDefault("enabled", p.enabled());
    if (!List.of("ADMIN", "READER").contains(role)) throw new ResponseStatusException(BAD_REQUEST);
    if (p.role().equals("ADMIN")
        && (!enabled || !role.equals("ADMIN"))
        && db.queryForObject(
                "select count(*) from archdeck.app_users where role='ADMIN' and enabled",
                Integer.class)
            <= 1) throw new ResponseStatusException(CONFLICT, "last_admin");
    String hash = p.hash();
    boolean change = p.mustChangePassword();
    if (b.containsKey("password")) {
      String password = Objects.toString(b.get("password"), "");
      passwordPolicy(password);
      hash = encoder.encode(password);
      change = true;
    }
    db.update(
        "update archdeck.app_users set"
            + " role=?,enabled=?,display_name=?,password_hash=?,must_change_password=?,auth_version=auth_version+1,lock_version=lock_version+1"
            + " where id=?::uuid",
        role,
        enabled,
        b.getOrDefault("displayName", p.displayName()),
        hash,
        change,
        id);
    revoke(id);
    return byId(id).view();
  }

  @Transactional
  public void change(Principal p, String current, String password) {
    if (!encoder.matches(current, byId(p.id()).hash()))
      throw new ResponseStatusException(BAD_REQUEST, "invalid_current_password");
    passwordPolicy(password);
    db.update(
        "update archdeck.app_users set"
            + " password_hash=?,must_change_password=false,auth_version=auth_version+1,password_changed_at=now()"
            + " where id=?::uuid",
        encoder.encode(password),
        p.id());
    revoke(p.id());
  }

  private void revoke(String id) {
    db.update("delete from archdeck.spring_session where principal_name=?", id);
  }
}
