package io.archdeck.backend.service;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Topic changes are patches over an immutable canonical snapshot, never model-owned documents. */
@Service
public class TopicRevisions {
  private final JdbcTemplate db;
  private final ContentStore store;
  private final ObjectMapper json;
  private static final Set<String> FIELDS = Set.of("title", "summary", "overview", "keyTakeaways");

  public TopicRevisions(JdbcTemplate db, ContentStore store, ObjectMapper json) {
    this.db = db;
    this.store = store;
    this.json = json;
  }

  public String contentIdentity(String uuid) {
    return db.queryForObject(
        "select coalesce(payload->>'legacyId',id::text) from archdeck.content_items where"
            + " id=?::uuid",
        String.class,
        uuid);
  }

  private JsonNode canonical(JsonNode value) {
    if (value.isObject()) {
      ObjectNode sorted = json.createObjectNode();
      TreeSet<String> keys = new TreeSet<>();
      value.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) sorted.set(key, canonical(value.path(key)));
      return sorted;
    }
    if (value.isArray()) {
      ArrayNode sorted = json.createArrayNode();
      for (JsonNode item : value) sorted.add(canonical(item));
      return sorted;
    }
    return value;
  }

  private String fingerprint(JsonNode topic) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(store.string(canonical(topic)).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void attach(ObjectNode candidate, JsonNode target, JsonNode patch) {
    if (!patch.isObject() || patch.isEmpty())
      throw new ResponseStatusException(BAD_REQUEST, "revision_patch_required");
    ObjectNode before = (ObjectNode) target.path("topic").deepCopy();
    ObjectNode after = before.deepCopy();
    patch
        .fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              JsonNode value = entry.getValue();
              if (!FIELDS.contains(key))
                throw new ResponseStatusException(BAD_REQUEST, "revision_field_not_allowed");
              if (key.equals("keyTakeaways")) {
                if (!value.isArray() || value.size() > 30)
                  throw new ResponseStatusException(BAD_REQUEST, "invalid_revision_patch");
                for (JsonNode n : value)
                  if (!n.isTextual() || n.asText().isBlank() || n.asText().length() > 4000)
                    throw new ResponseStatusException(BAD_REQUEST, "invalid_revision_patch");
              } else if (!value.isTextual()
                  || value.asText().isBlank()
                  || value.asText().length() > 50000)
                throw new ResponseStatusException(BAD_REQUEST, "invalid_revision_patch");
              after.set(key, value);
            });
    candidate.put("proposalKind", "topic_revision");
    candidate.put("targetContentId", before.path("id").asText());
    candidate.put("baseLockVersion", target.path("baseLockVersion").asLong());
    candidate.put("baseFingerprint", fingerprint(before));
    candidate.set("baseTopic", before);
    candidate.set("revisionPatch", patch.deepCopy());
    candidate.set("proposedTopic", after);
  }

  private ObjectNode candidate(String id, boolean lock) {
    var rows =
        db.queryForList(
            "select payload::text,version,status from archdeck.knowledge_candidates where"
                + " id=?::uuid"
                + (lock ? " for update" : ""),
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
    var row = rows.getFirst();
    ObjectNode p = (ObjectNode) store.parse(row.get("payload").toString());
    p.put("candidateVersion", ((Number) row.get("version")).longValue());
    p.put("status", row.get("status").toString());
    return p;
  }

  public JsonNode diff(String id) {
    ObjectNode p = candidate(id, false), result = json.createObjectNode();
    result.put("candidateVersion", p.path("candidateVersion").asLong());
    result.put("proposalKind", p.path("proposalKind").asText("insight"));
    ArrayNode changes = result.putArray("changes");
    if (!p.path("proposalKind").asText().equals("topic_revision")) {
      result.put("conflict", false);
      return result;
    }
    result.put("targetContentId", p.path("targetContentId").asText());
    result.put("baseLockVersion", p.path("baseLockVersion").asLong());
    JsonNode current = null;
    try {
      current = store.get("topic", p.path("targetContentId").asText());
    } catch (ResponseStatusException ignored) {
    }
    if (current != null) result.put("currentLockVersion", current.path("lockVersion").asLong());
    else result.putNull("currentLockVersion");
    result.put(
        "conflict",
        current == null
            || current.path("lockVersion").asLong() != p.path("baseLockVersion").asLong()
            || !fingerprint(current).equals(p.path("baseFingerprint").asText()));
    result.set("before", p.path("baseTopic"));
    result.set("after", p.path("proposedTopic"));
    for (String key : new TreeSet<>(FIELDS))
      if (!p.path("baseTopic").path(key).equals(p.path("proposedTopic").path(key))) {
        ObjectNode change = changes.addObject();
        change.put("path", "/" + key);
        change.set("before", p.path("baseTopic").path(key));
        change.set("after", p.path("proposedTopic").path(key));
      }
    return result;
  }

  @Transactional
  public JsonNode revise(String id, JsonNode body) {
    ObjectNode p = candidate(id, true);
    if (p.path("status").asText().equals("published")
        || p.path("candidateVersion").asLong() != body.path("candidateVersion").asLong(-1))
      throw new ResponseStatusException(CONFLICT, "candidate_conflict");
    JsonNode topic = store.get("topic", body.path("targetContentId").asText());
    if (topic.path("lockVersion").asLong() != body.path("baseLockVersion").asLong(-1))
      throw new ResponseStatusException(CONFLICT, "revision_conflict");
    ObjectNode target = json.createObjectNode();
    target.set("topic", topic);
    target.put("baseLockVersion", topic.path("lockVersion").asLong());
    attach(p, target, body.path("revisionPatch"));
    p.remove(List.of("candidateVersion", "status"));
    db.update(
        "update archdeck.knowledge_candidates set"
            + " payload=?::jsonb,status='needs_review',version=version+1,reviewer_id=null,review_reason=null"
            + " where id=?::uuid",
        store.string(p),
        id);
    return diff(id);
  }

  public JsonNode publish(ObjectNode candidate, JsonNode request, String author) {
    String target = candidate.path("targetContentId").asText();
    var rows =
        db.queryForList(
            "select id from archdeck.content_items where id=? and kind='topic' and"
                + " status='published' for update",
            store.identity(target));
    if (rows.isEmpty()) throw new ResponseStatusException(CONFLICT, "target_unavailable");
    JsonNode current = store.get("topic", target);
    long expected = candidate.path("baseLockVersion").asLong(-1);
    if (request.path("baseLockVersion").asLong(-2) != expected
        || current.path("lockVersion").asLong() != expected
        || !fingerprint(current).equals(candidate.path("baseFingerprint").asText()))
      throw new ResponseStatusException(CONFLICT, "revision_conflict");
    ObjectNode canonical = current.deepCopy();
    // Reapply the validated patch to the current canonical object; submitted full documents are
    // never trusted.
    ObjectNode temp = json.createObjectNode(), targetNode = json.createObjectNode();
    targetNode.set("topic", current);
    targetNode.put("baseLockVersion", expected);
    attach(temp, targetNode, candidate.path("revisionPatch"));
    canonical = (ObjectNode) temp.path("proposedTopic");
    if (canonical.equals(current))
      throw new ResponseStatusException(BAD_REQUEST, "revision_has_no_changes");
    return store.save("topic", canonical, target, author);
  }
}
