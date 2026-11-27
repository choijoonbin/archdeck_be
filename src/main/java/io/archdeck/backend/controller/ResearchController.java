package io.archdeck.backend.controller;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.archdeck.backend.service.*;
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
public class ResearchController {
  private final JdbcTemplate db;
  private final ContentStore store;
  private final ObjectMapper json;
  private final DistributedResearch queue;
  private final TopicRevisions revisions;
  private final String agentUrl, token;

  public ResearchController(
      JdbcTemplate d,
      ContentStore s,
      ObjectMapper j,
      DistributedResearch q,
      TopicRevisions rev,
      @Value("${ARCHDECK_AGENT_URL:http://127.0.0.1:4010}") String url,
      @Value("${ARCHDECK_AGENT_SERVICE_TOKEN:}") String t) {
    queue = q;
    revisions = rev;
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
          m.put("leaseOwner", r.getString("lease_owner"));
          m.put("leaseFence", r.getLong("lease_fence"));
          m.put("leaseUntil", r.getTimestamp("lease_until"));
          m.put("heartbeatAt", r.getTimestamp("heartbeat_at"));
          m.put("attempt", r.getInt("attempt"));
          return m;
        });
  }

  @PostMapping("/runs")
  Map<String, Object> run(@RequestBody Map<String, String> b) {
    String policyId = b.get("policyId");
    JsonNode p =
        policies().stream()
            .filter(x -> x.path("id").asText().equals(policyId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    if (token.isBlank())
      throw new ResponseStatusException(SERVICE_UNAVAILABLE, "agent_not_configured");
    return queue.enqueue(p, null);
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
          if (!p.has("baseLockVersion")) p.putNull("baseLockVersion");
          if (!p.has("proposalKind")) p.put("proposalKind", "insight");
          return p;
        });
  }

  @PostMapping("/candidates/{id}/review")
  void review(
      @PathVariable String id,
      @RequestBody JsonNode b,
      @AuthenticationPrincipal Accounts.Principal user) {
    if (!List.of("reject", "request_changes", "comment").contains(b.path("action").asText()))
      throw new ResponseStatusException(BAD_REQUEST);
    int updated =
        db.update(
            "update archdeck.knowledge_candidates set"
                + " status=?,reviewer_id=?::uuid,review_reason=?,version=version+1 where id=?::uuid"
                + " and status<>'published' and version=?",
            b.path("action").asText().equals("comment")
                ? "needs_review"
                : b.path("action").asText(),
            user.id(),
            b.path("reason").asText(),
            id,
            b.path("candidateVersion").asLong(-1));
    if (updated != 1) throw new ResponseStatusException(CONFLICT, "candidate_conflict");
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
      return store.get(
          store
                  .parse(row.get("payload").toString())
                  .path("proposalKind")
                  .asText()
                  .equals("topic_revision")
              ? "topic"
              : "insight",
          revisions.contentIdentity(row.get("published_content_id").toString()));
    if (!row.get("status").equals("needs_review")
        || ((Number) row.get("version")).longValue() != b.path("candidateVersion").asLong())
      throw new ResponseStatusException(CONFLICT);
    if (b.path("reason").asText().isBlank())
      throw new ResponseStatusException(BAD_REQUEST, "reason_required");
    var p = (ObjectNode) store.parse(row.get("payload").toString());
    validateEvidence(p);
    if (!p.path("issues").isEmpty() || p.path("claims").isEmpty())
      throw new ResponseStatusException(BAD_REQUEST, "evidence_required");
    boolean topicRevision = p.path("proposalKind").asText().equals("topic_revision");
    String content;
    JsonNode saved;
    if (topicRevision) {
      saved = revisions.publish(p, b, user.id());
      content = store.identity(saved.path("id").asText()).toString();
    } else {
      content = UUID.randomUUID().toString();
      p.put("id", content);
      p.put("slug", "insight-" + content);
      p.put("publishedAt", Instant.now().toString());
      saved = store.save("insight", p, null, user.id());
    }
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

  @PostMapping("/runs/{id}/cancel")
  void cancel(@PathVariable String id) {
    queue.cancel(id);
  }

  @GetMapping("/candidates/{id}/diff")
  JsonNode diff(@PathVariable String id) {
    return revisions.diff(id);
  }

  @PostMapping("/candidates/{id}/revision")
  JsonNode revision(@PathVariable String id, @RequestBody JsonNode body) {
    return revisions.revise(id, body);
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
      queue.enqueue(policy, slot);
    }
  }
}
