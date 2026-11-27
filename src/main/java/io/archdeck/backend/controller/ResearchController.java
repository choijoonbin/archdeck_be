package io.archdeck.backend.controller;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.archdeck.backend.service.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.scheduling.annotation.EnableScheduling
@RestController
@RequestMapping("/api/research")
public class ResearchController implements org.springframework.boot.ApplicationRunner {
  private final JdbcTemplate db;
  private final ContentStore store;
  private final ObjectMapper json;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final String agentUrl, token;

  public ResearchController(
      JdbcTemplate d,
      ContentStore s,
      ObjectMapper j,
      @Value("${ARCHDECK_AGENT_URL:http://127.0.0.1:4010}") String url,
      @Value("${ARCHDECK_AGENT_SERVICE_TOKEN:}") String t) {
    db = d;
    store = s;
    json = j;
    agentUrl = url;
    token = t;
  }

  @GetMapping("/policies")
  List<JsonNode> policies() {
    return db.query(
        "select id,payload::text,version from archdeck.research_policies",
        (r, n) -> {
          var p = (ObjectNode) store.parse(r.getString(2));
          p.put("id", r.getString(1));
          p.put("version", r.getLong(3));
          return p;
        });
  }

  @PostMapping("/policies")
  JsonNode policy(@RequestBody ObjectNode p) {
    String id = p.path("id").asText(UUID.randomUUID().toString());
    if (p.path("name").asText().isBlank() || p.path("sources").isEmpty())
      throw new ResponseStatusException(BAD_REQUEST, "name_and_sources_required");
    db.update(
        "insert into archdeck.research_policies(id,payload)values(?::uuid,?::jsonb)on"
            + " conflict(id)do update set"
            + " payload=excluded.payload,version=research_policies.version+1",
        id,
        store.string(p));
    return policies().stream()
        .filter(x -> x.path("id").asText().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @GetMapping("/runs")
  List<Map<String, Object>> runs() {
    return db.query(
        "select * from archdeck.research_runs order by created_at desc limit 100",
        (r, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", r.getString("id"));
          m.put("policyId", r.getString("policy_id"));
          m.put("status", r.getString("status"));
          m.put("createdAt", r.getTimestamp("created_at").toInstant().toString());
          m.put(
              "result", r.getString("result") == null ? null : store.parse(r.getString("result")));
          m.put("error", r.getString("error"));
          return m;
        });
  }

  @PostMapping("/runs")
  Map<String, Object> run(@RequestBody Map<String, String> b) {
    if (db.queryForObject(
            "select count(*) from archdeck.research_runs where status in ('queued','running')",
            Integer.class)
        >= 20) throw new ResponseStatusException(TOO_MANY_REQUESTS, "research_queue_full");
    String policyId = b.get("policyId");
    JsonNode p =
        policies().stream()
            .filter(x -> x.path("id").asText().equals(policyId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    if (token.isBlank())
      throw new ResponseStatusException(SERVICE_UNAVAILABLE, "agent_not_configured");
    String id = UUID.randomUUID().toString();
    db.update(
        "insert into"
            + " archdeck.research_runs(id,policy_id,status,policy_snapshot)values(?::uuid,?::uuid,'queued',?::jsonb)",
        id,
        policyId,
        store.string(p));
    worker.submit(() -> execute(id, p));
    return Map.of("id", id, "status", "queued");
  }

  void execute(String id, JsonNode p) {
    try {
      int claimed =
          db.update(
              "update archdeck.research_runs set status='running'where id=?::uuid and"
                  + " status='queued'",
              id);
      if (claimed == 0) return;
      var policy = json.createObjectNode();
      policy.put("domain_id", p.path("domainId").asText("agent-engineering"));
      policy.put("version", p.path("version").asInt(1));
      policy.put("lookback_days", p.path("lookbackDays").asInt(14));
      policy.put("max_documents", p.path("maxDocuments").asInt(5));
      policy.put("max_input_characters", p.path("maxInputCharacters").asInt(20000));
      policy.set("sources", p.path("sources"));
      policy.put("auto_publish", false);
      var request = json.createObjectNode();
      request.put("runId", id);
      request.put("leaseFence", 1);
      request.put(
          "asOf",
          db.queryForObject(
                  "select created_at from archdeck.research_runs where id=?::uuid",
                  java.sql.Timestamp.class,
                  id)
              .toInstant()
              .toString());
      request.set("policy", policy);
      var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(agentUrl + "/internal/runs/" + id + "/execute"))
                  .timeout(Duration.ofSeconds(150))
                  .header("Authorization", "Bearer " + token)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(store.string(request)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("agent_http_" + response.statusCode());
      var result = store.parse(response.body());
      db.update(
          "update archdeck.research_runs set status=?,result=?::jsonb where id=?::uuid and"
              + " status='running'",
          result.path("status").asText("failed"),
          store.string(result),
          id);
      if (result.path("status").asText().equals("needs_review")
          && "needs_review"
              .equals(
                  db.queryForObject(
                      "select status from archdeck.research_runs where id=?::uuid",
                      String.class,
                      id))) {
        var candidate = (ObjectNode) result.path("draft").deepCopy();
        candidate.set("documents", result.path("documents"));
        candidate.set("issues", result.path("issues"));
        db.update(
            "insert into"
                + " archdeck.knowledge_candidates(id,run_id,payload)values(?::uuid,?::uuid,?::jsonb)",
            UUID.randomUUID().toString(),
            id,
            store.string(candidate));
      }
    } catch (Exception e) {
      db.update(
          "update archdeck.research_runs set status='failed',error='agent_execution_failed'where"
              + " id=?::uuid and status='running'",
          id);
    }
  }

  @GetMapping("/candidates")
  List<JsonNode> candidates() {
    return db.query(
        "select * from archdeck.knowledge_candidates order by created_at desc",
        (r, n) -> {
          var p = (ObjectNode) store.parse(r.getString("payload"));
          p.put("id", r.getString("id"));
          p.put("runId", r.getString("run_id"));
          p.put("status", r.getString("status"));
          p.put("candidateVersion", r.getLong("version"));
          p.putNull("baseLockVersion");
          return p;
        });
  }

  @PostMapping("/candidates/{id}/review")
  void review(
      @PathVariable String id,
      @RequestBody Map<String, String> b,
      @AuthenticationPrincipal Accounts.Principal user) {
    if (!List.of("reject", "request_changes", "comment").contains(b.get("action")))
      throw new ResponseStatusException(BAD_REQUEST);
    db.update(
        "update archdeck.knowledge_candidates set"
            + " status=?,reviewer_id=?::uuid,review_reason=?,version=version+1 where id=?::uuid and"
            + " status<>'published'",
        b.get("action"),
        user.id(),
        b.getOrDefault("reason", ""),
        id);
  }

  @Transactional
  @PostMapping("/candidates/{id}/publish")
  JsonNode publish(
      @PathVariable String id,
      @RequestBody JsonNode b,
      @RequestHeader(value = "Idempotency-Key", required = false) String publicationKey,
      @AuthenticationPrincipal Accounts.Principal user) {
    if (publicationKey == null || publicationKey.isBlank()) publicationKey = "candidate-" + id;
    var rows =
        db.queryForList(
            "select * from archdeck.knowledge_candidates where id=?::uuid for update", id);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
    var row = rows.getFirst();
    if (row.get("status").equals("published"))
      return store.get("insight", row.get("published_content_id").toString());
    if (!row.get("status").equals("needs_review")
        || ((Number) row.get("version")).longValue() != b.path("candidateVersion").asLong())
      throw new ResponseStatusException(CONFLICT);
    if (b.path("reason").asText().isBlank())
      throw new ResponseStatusException(BAD_REQUEST, "reason_required");
    var p = (ObjectNode) store.parse(row.get("payload").toString());
    validateEvidence(p);
    if (!p.path("issues").isEmpty() || p.path("claims").isEmpty())
      throw new ResponseStatusException(BAD_REQUEST, "evidence_required");
    String content = UUID.randomUUID().toString();
    p.put("id", content);
    p.put("slug", "insight-" + content);
    p.put("publishedAt", Instant.now().toString());
    JsonNode saved = store.save("insight", p, null, user.id());
    for (JsonNode source : p.path("documents")) {
      String url = source.path("url").asText();
      String sourceId =
          UUID.nameUUIDFromBytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
      db.update(
          "insert into"
              + " archdeck.sources(id,canonical_url,title,source_type)values(?::uuid,?,?,'article')"
              + " on conflict(canonical_url) do nothing",
          sourceId,
          url,
          source.path("title").asText("Research source"));
      db.update(
          "insert into archdeck.content_sources(content_id,source_id)select ?::uuid,id from"
              + " archdeck.sources where canonical_url=? on conflict do nothing",
          content,
          url);
    }
    db.update(
        "update archdeck.knowledge_candidates set"
            + " status='published',published_content_id=?::uuid,reviewer_id=?::uuid,review_reason=?,publication_key=?"
            + " where id=?::uuid",
        content,
        user.id(),
        b.path("reason").asText(),
        publicationKey,
        id);
    return saved;
  }

  public void run(org.springframework.boot.ApplicationArguments args) {
    db.update(
        "update archdeck.research_runs set status='failed',error='interrupted_restart'where"
            + " status='running'");
    for (var row :
        db.queryForList(
            "select id,policy_snapshot::text from archdeck.research_runs where status='queued' and"
                + " policy_snapshot is not null"))
      worker.submit(
          () ->
              execute(
                  row.get("id").toString(), store.parse(row.get("policy_snapshot").toString())));
  }

  @PostMapping("/runs/{id}/cancel")
  void cancel(@PathVariable String id) {
    db.update(
        "update archdeck.research_runs set status='cancelled' where id=?::uuid and status in"
            + " ('queued','running')",
        id);
    try {
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create(agentUrl + "/internal/runs/" + id + "/cancel"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Bearer " + token)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.discarding());
    } catch (Exception ignored) {
    }
  }

  void validateEvidence(JsonNode candidate) {
    Map<String, JsonNode> docs = new HashMap<>();
    for (JsonNode d : candidate.path("documents")) docs.put(d.path("source_id").asText(), d);
    for (JsonNode claim : candidate.path("claims")) {
      if (claim.path("evidence").isEmpty())
        throw new ResponseStatusException(BAD_REQUEST, "missing_evidence");
      for (JsonNode evidence : claim.path("evidence")) {
        var source = docs.get(evidence.path("source_id").asText());
        String quote = evidence.path("quote").asText();
        if (source == null
            || quote.isBlank()
            || !source.path("excerpt").asText().contains(quote)
            || !source.path("reuse_allowed").asBoolean())
          throw new ResponseStatusException(BAD_REQUEST, "invalid_evidence");
      }
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void scheduledRuns() {
    if (token.isBlank()) return;
    for (JsonNode policy : policies()) {
      if (!policy.path("scheduleEnabled").asBoolean(false)) continue;
      int interval = policy.path("intervalMinutes").asInt(1440);
      if (interval < 15 || interval > 10080) continue;
      String slot = Long.toString(Instant.now().getEpochSecond() / (interval * 60L));
      String id = UUID.randomUUID().toString();
      int inserted =
          db.update(
              "insert into"
                  + " archdeck.research_runs(id,policy_id,status,policy_snapshot,schedule_slot)values(?::uuid,?::uuid,'queued',?::jsonb,?)"
                  + " on conflict do nothing",
              id,
              policy.path("id").asText(),
              store.string(policy),
              slot);
      if (inserted == 1) worker.submit(() -> execute(id, policy));
    }
  }
}
