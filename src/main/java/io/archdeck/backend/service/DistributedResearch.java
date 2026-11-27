package io.archdeck.backend.service;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL owns execution rights. HTTP delivery is at least once; acceptance is fenced. */
@Service
public class DistributedResearch {
  private final JdbcTemplate db;
  private final ContentStore store;
  private final TopicRevisions revisions;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final String url, token, owner = UUID.randomUUID().toString();
  private final int leaseSeconds;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public DistributedResearch(
      JdbcTemplate db,
      ContentStore store,
      TopicRevisions revisions,
      ObjectMapper json,
      org.springframework.transaction.PlatformTransactionManager manager,
      @Value("${ARCHDECK_AGENT_URL:http://127.0.0.1:4010}") String url,
      @Value("${ARCHDECK_AGENT_SERVICE_TOKEN:}") String token,
      @Value("${ARCHDECK_RUN_LEASE_SECONDS:45}") int leaseSeconds) {
    this.db = db;
    this.store = store;
    this.revisions = revisions;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    this.url = url;
    this.token = token;
    this.leaseSeconds = Math.max(6, leaseSeconds);
  }

  public Map<String, Object> enqueue(JsonNode policy, String slot) {
    return tx.execute(
        status -> {
          db.queryForObject("select pg_advisory_xact_lock(7483921)", Object.class);
          if (slot == null
              && db.queryForObject(
                      "select count(*) from archdeck.research_runs where status in"
                          + " ('queued','running')",
                      Integer.class)
                  >= 20)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "research_queue_full");
          String id = UUID.randomUUID().toString();
          ObjectNode request = wire(id, policy);
          int inserted =
              db.update(
                  "insert into"
                      + " archdeck.research_runs(id,policy_id,status,policy_snapshot,schedule_slot)"
                      + " values(?::uuid,?::uuid,'queued',?::jsonb,?) on conflict do nothing",
                  id,
                  policy.path("id").asText(),
                  store.string(policy),
                  slot);
          if (inserted == 1)
            db.update(
                "insert into archdeck.research_commands(id,run_id,command_type,payload)"
                    + " values(?::uuid,?::uuid,'execute',?::jsonb)",
                UUID.randomUUID().toString(),
                id,
                store.string(request));
          return Map.of("id", id, "status", inserted == 1 ? "queued" : "already_scheduled");
        });
  }

  private ObjectNode wire(String id, JsonNode p) {
    ObjectNode policy = json.createObjectNode();
    policy.put("domain_id", p.path("domainId").asText("agent-engineering"));
    policy.put("version", p.path("version").asInt(1));
    policy.put("lookback_days", p.path("lookbackDays").asInt(14));
    policy.put("max_documents", p.path("maxDocuments").asInt(5));
    policy.put("max_input_characters", p.path("maxInputCharacters").asInt(20000));
    policy.set("sources", p.path("sources"));
    policy.put("auto_publish", false);
    ObjectNode request = json.createObjectNode();
    request.put("runId", id);
    request.put("asOf", Instant.now().toString());
    request.set("policy", policy);
    if (!p.path("targetContentId").asText().isBlank()) {
      JsonNode topic = store.get("topic", p.path("targetContentId").asText());
      if (p.hasNonNull("targetBaseLockVersion")
          && p.path("targetBaseLockVersion").asLong() != topic.path("lockVersion").asLong())
        throw new ResponseStatusException(CONFLICT, "revision_conflict");
      ObjectNode target = request.putObject("targetTopic");
      target.put("targetContentId", topic.path("id").asText());
      target.put("baseLockVersion", topic.path("lockVersion").asLong());
      target.set("topic", topic);
    }
    return request;
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 1500)
  public void poll() {
    if (token.isBlank()) return;
    deliverCancellations();
    if (!busy.compareAndSet(false, true)) return;
    try {
      Map<String, Object> claim =
          tx.execute(
              status -> {
                var rows =
                    db.queryForList(
                        "select r.*,c.payload::text as command_payload from archdeck.research_runs"
                            + " r join archdeck.research_commands c on c.run_id=r.id and"
                            + " c.command_type='execute' where c.state<>'completed' and"
                            + " c.available_at<=clock_timestamp() and (r.status='queued' or"
                            + " (r.status='running' and (r.lease_until is null or"
                            + " r.lease_until<clock_timestamp()))) order by r.created_at for update"
                            + " of r skip locked limit 1");
                if (rows.isEmpty()) return null;
                var row = rows.getFirst();
                String id = row.get("id").toString();
                ObjectNode command =
                    (ObjectNode) store.parse(row.get("command_payload").toString());
                if (command.has("legacyPolicy")) {
                  command = wire(id, command.path("legacyPolicy"));
                  command.put(
                      "asOf", ((java.sql.Timestamp) row.get("created_at")).toInstant().toString());
                  String frozen = store.string(command);
                  row.put("command_payload", frozen);
                  db.update(
                      "update archdeck.research_commands set payload=?::jsonb where run_id=?::uuid"
                          + " and command_type='execute'",
                      frozen,
                      id);
                }
                db.update(
                    "update archdeck.research_runs set"
                        + " status='running',lease_owner=?,lease_until=clock_timestamp()+(? *"
                        + " interval '1"
                        + " second'),heartbeat_at=clock_timestamp(),lease_fence=lease_fence+1,attempt=attempt+1,error=null"
                        + " where id=?::uuid",
                    owner,
                    leaseSeconds,
                    id);
                row.put("claimed_fence", ((Number) row.get("lease_fence")).longValue() + 1);
                return row;
              });
      if (claim == null) busy.set(false);
      else worker.submit(() -> execute(claim));
    } catch (Exception e) {
      busy.set(false);
    }
  }

  private void execute(Map<String, Object> row) {
    String id = row.get("id").toString();
    long fence = ((Number) row.get("claimed_fence")).longValue();
    ScheduledFuture<?> renewal =
        heartbeat.scheduleAtFixedRate(
            () -> {
              try {
                db.update(
                    "update archdeck.research_runs set lease_until=clock_timestamp()+(? * interval"
                        + " '1 second'),heartbeat_at=clock_timestamp() where id=?::uuid and"
                        + " lease_owner=? and lease_fence=? and status='running' and"
                        + " lease_until>clock_timestamp()",
                    leaseSeconds,
                    id,
                    owner,
                    fence);
              } catch (Exception ignored) {
              }
            },
            leaseSeconds / 3,
            leaseSeconds / 3,
            TimeUnit.SECONDS);
    try {
      ObjectNode request = (ObjectNode) store.parse(row.get("command_payload").toString());
      request.put("leaseFence", fence);
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(url + "/internal/runs/" + id + "/execute"))
                  .timeout(Duration.ofSeconds(180))
                  .header("Authorization", "Bearer " + token)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(store.string(request)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("agent_http_" + response.statusCode());
      JsonNode result = store.parse(response.body());
      if (!id.equals(result.path("runId").asText()) || result.path("leaseFence").asLong() != fence)
        throw new IllegalStateException("agent_identity_mismatch");
      ObjectNode frozen = request;
      tx.executeWithoutResult(
          status -> {
            var locked =
                db.queryForList(
                    "select id from archdeck.research_runs where id=?::uuid and status='running'"
                        + " and lease_owner=? and lease_fence=? and lease_until>clock_timestamp()"
                        + " for update",
                    id,
                    owner,
                    fence);
            if (locked.isEmpty()) return;
            // Recheck after acquiring the row lock: a lock wait may outlive the lease.
            if (!Boolean.TRUE.equals(
                db.queryForObject(
                    "select lease_until>clock_timestamp() from archdeck.research_runs where"
                        + " id=?::uuid",
                    Boolean.class,
                    id))) return;
            if (db.update(
                    "insert into archdeck.research_inbox(run_id,lease_fence,payload)"
                        + " values(?::uuid,?,?::jsonb) on conflict do nothing",
                    id,
                    fence,
                    store.string(result))
                == 0) return;
            String state = result.path("status").asText();
            if (!List.of("needs_review", "held", "cancelled", "failed").contains(state))
              throw new IllegalStateException("agent_invalid_status");
            if (state.equals("needs_review")) {
              ObjectNode candidate = (ObjectNode) result.path("draft").deepCopy();
              candidate.set("documents", result.path("documents"));
              candidate.set("issues", result.path("issues"));
              candidate.put("proposalKind", "insight");
              if (frozen.has("targetTopic"))
                revisions.attach(
                    candidate, frozen.path("targetTopic"), candidate.path("revisionPatch"));
              db.update(
                  "insert into archdeck.knowledge_candidates(id,run_id,payload)"
                      + " values(?::uuid,?::uuid,?::jsonb) on conflict(run_id) do nothing",
                  UUID.randomUUID().toString(),
                  id,
                  store.string(candidate));
            }
            db.update(
                "update archdeck.research_runs set status=?,result=?::jsonb,lease_until=null where"
                    + " id=?::uuid",
                state,
                store.string(result),
                id);
            db.update(
                "update archdeck.research_commands set state='completed' where run_id=?::uuid and"
                    + " command_type='execute'",
                id);
          });
    } catch (Exception e) {
      tx.executeWithoutResult(
          status -> {
            // A transport failure may have happened after remote completion: replay the same frozen
            // command.
            int changed =
                db.update(
                    "update archdeck.research_runs set status=case when attempt>=5 then 'failed'"
                        + " else 'queued' end,error='agent_execution_failed',lease_until=null where"
                        + " id=?::uuid and status='running' and lease_owner=? and lease_fence=? and"
                        + " lease_until>clock_timestamp()",
                    id,
                    owner,
                    fence);
            if (changed == 1)
              db.update(
                  "update archdeck.research_commands set state=case when (select status from"
                      + " archdeck.research_runs where id=?::uuid)='failed' then 'completed' else"
                      + " state end,available_at=clock_timestamp()+interval '5"
                      + " seconds',attempts=attempts+1 where run_id=?::uuid and"
                      + " command_type='execute'",
                  id,
                  id);
          });
    } finally {
      renewal.cancel(false);
      busy.set(false);
    }
  }

  public void cancel(String id) {
    tx.executeWithoutResult(
        status -> {
          var rows =
              db.queryForList(
                  "select lease_fence,status from archdeck.research_runs where id=?::uuid for"
                      + " update",
                  id);
          if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
          if (!List.of("queued", "running").contains(rows.getFirst().get("status"))) return;
          long fence = ((Number) rows.getFirst().get("lease_fence")).longValue();
          db.update(
              "update archdeck.research_runs set status='cancelled',lease_until=null where"
                  + " id=?::uuid",
              id);
          db.update(
              "update archdeck.research_commands set state='completed' where run_id=?::uuid and"
                  + " command_type='execute'",
              id);
          db.update(
              "insert into archdeck.research_commands(id,run_id,command_type,payload)"
                  + " values(?::uuid,?::uuid,'cancel',?::jsonb) on conflict do nothing",
              UUID.randomUUID().toString(),
              id,
              "{\"leaseFence\":" + fence + "}");
        });
  }

  private void deliverCancellations() {
    var rows =
        db.queryForList(
            "with picked as(select id from archdeck.research_commands where command_type='cancel'"
                + " and state<>'completed' and available_at<=clock_timestamp() and (lease_until is"
                + " null or lease_until<clock_timestamp()) order by created_at for update skip"
                + " locked limit 5) update archdeck.research_commands c set"
                + " lease_until=clock_timestamp()+interval '10 seconds',attempts=attempts+1 from"
                + " picked where c.id=picked.id returning c.*");
    for (var c : rows)
      try {
        var response =
            http.send(
                HttpRequest.newBuilder(
                        URI.create(url + "/internal/runs/" + c.get("run_id") + "/cancel"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(c.get("payload").toString()))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 == 2)
          db.update(
              "update archdeck.research_commands set state='completed',lease_until=null where id=?",
              c.get("id"));
        else
          db.update(
              "update archdeck.research_commands set available_at=clock_timestamp()+interval '10"
                  + " seconds',lease_until=null where id=?",
              c.get("id"));
      } catch (Exception ignored) {
      }
  }

  @PreDestroy
  public void close() {
    worker.shutdownNow();
    heartbeat.shutdownNow();
  }
}
