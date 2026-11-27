package io.archdeck.backend.init;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.archdeck.backend.service.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Bootstrap implements ApplicationRunner {
  private final Accounts accounts;
  private final ContentStore store;
  private final ObjectMapper json;
  private final JdbcTemplate db;

  public Bootstrap(Accounts a, ContentStore s, ObjectMapper j, JdbcTemplate d) {
    accounts = a;
    store = s;
    json = j;
    db = d;
  }

  public void run(ApplicationArguments args) throws Exception {
    String bind = System.getenv().getOrDefault("ARCHDECK_BIND_ADDRESS", "127.0.0.1");
    if (!Set.of("127.0.0.1", "localhost", "::1").contains(bind)
        && !"true".equals(System.getenv("ARCHDECK_SECURE_COOKIE")))
      throw new IllegalStateException(
          "Non-loopback sharing requires secure cookies and HTTPS proxy");
    String username = System.getenv("ARCHDECK_BOOTSTRAP_USERNAME"),
        password = System.getenv("ARCHDECK_BOOTSTRAP_PASSWORD");
    if (username != null
        && password != null
        && db.queryForObject(
                "select count(*) from archdeck.app_users where role='ADMIN'", Integer.class)
            == 0)
      accounts.create(
          Map.of(
              "username", username, "password", password, "displayName", "관리자", "role", "ADMIN"));
    if (db.queryForObject("select count(*) from archdeck.content_items", Integer.class) == 0) {
      var resource = getClass().getResourceAsStream("/content-seed.json");
      if (resource == null) return;
      JsonNode data = json.readTree(resource);
      for (String key : List.of("topics", "comparisons", "glossary", "checklists")) {
        String kind =
            switch (key) {
              case "topics" -> "topic";
              case "comparisons" -> "comparison";
              case "glossary" -> "glossary";
              default -> "checklist";
            };
        for (JsonNode n : data.path(key)) {
          ObjectNode copy = (ObjectNode) n.deepCopy();
          if (kind.equals("topic") && copy.has("securityChecklistIds")) {
            ArrayNode ids = json.createArrayNode();
            for (JsonNode v : copy.path("securityChecklistIds"))
              if (!List.of("chk-iam-2", "chk-iam-3").contains(v.asText())) ids.add(v);
            copy.set("securityChecklistIds", ids);
          }
          store.save(kind, copy, null, null);
        }
      }
    }
  }
}
