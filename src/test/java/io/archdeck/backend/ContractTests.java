package io.archdeck.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.archdeck.backend.service.Accounts;
import io.archdeck.backend.service.ContentStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class ContractTests {
  @Test
  void nonTruncatedUnicodePasswordsAndLimits() {
    Accounts.passwordPolicy("한글과 공백을 포함하는 안전한 암호입니다");
    assertThrows(ResponseStatusException.class, () -> Accounts.passwordPolicy("short"));
    assertThrows(ResponseStatusException.class, () -> Accounts.passwordPolicy("a".repeat(129)));
    var encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    String password = "a".repeat(90) + "one";
    String hash = encoder.encode(password);
    assertTrue(encoder.matches(password, hash));
    assertFalse(encoder.matches("a".repeat(90) + "two", hash));
  }

  @Test
  void sessionPrincipalSerializationDoesNotContainPasswordHash() throws Exception {
    var principal =
        new Accounts.Principal(
            "id", "name", "Display", "READER", "secret-hash-marker", true, false, 1);
    var output = new java.io.ByteArrayOutputStream();
    new java.io.ObjectOutputStream(output).writeObject(principal);
    assertFalse(
        new String(output.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains("secret-hash-marker"));
    principal.eraseCredentials();
    assertNull(principal.getPassword());
    assertEquals("id", principal.getUsername());
  }

  @Test
  void legacyIdsAreStableAndValidUuidsArePreserved() {
    var store = new ContentStore(mock(JdbcTemplate.class), new ObjectMapper());
    assertEquals(store.identity("pkce"), store.identity("pkce"));
    assertNotEquals(store.identity("pkce"), store.identity("csrf"));
    var id = java.util.UUID.randomUUID();
    assertEquals(id, store.identity(id.toString()));
  }

  @Test
  void invalidGraphAndContentNeverReachPersistence() throws Exception {
    var db = mock(JdbcTemplate.class);
    var json = new ObjectMapper();
    var store = new ContentStore(db, json);
    var topic = json.createObjectNode().put("title", "Example");
    topic.putArray("diagramNodes");
    topic
        .putArray("flowSteps")
        .addObject()
        .put("title", "Step")
        .putArray("activeNodes")
        .add("missing-node");
    assertThrows(ResponseStatusException.class, () -> store.save("topic", topic, null, null));
    verifyNoInteractions(db);
    var invalid = json.createObjectNode().put("title", "Example").put("tags", "not-array");
    assertThrows(ResponseStatusException.class, () -> store.save("glossary", invalid, null, null));
    verifyNoInteractions(db);
  }

  @Test
  void directedGraphRejectsUnknownEdgesDuplicateIdsAndInvalidCoordinatesBeforeWriting() {
    var db = mock(JdbcTemplate.class);
    var json = new ObjectMapper();
    var store = new ContentStore(db, json);
    var topic = json.createObjectNode().put("title", "Graph");
    topic.putArray("diagramNodes").addObject().put("id", "browser").put("label", "Browser");
    topic
        .putArray("diagramEdges")
        .addObject()
        .put("id", "bad")
        .put("source", "browser")
        .put("target", "missing");
    assertThrows(ResponseStatusException.class, () -> store.save("topic", topic, null, null));
    topic.withArray("diagramEdges").removeAll();
    topic
        .withArray("flowSteps")
        .addObject()
        .put("title", "Missing edge")
        .putArray("activeEdgeIds")
        .add("missing");
    assertThrows(ResponseStatusException.class, () -> store.save("topic", topic, null, null));
    topic.withArray("flowSteps").removeAll();
    ((com.fasterxml.jackson.databind.node.ObjectNode) topic.withArray("diagramNodes").get(0))
        .put("x", Double.NaN);
    assertThrows(ResponseStatusException.class, () -> store.save("topic", topic, null, null));
    verifyNoInteractions(db);
  }

  @Test
  void revisionPatchPreservesCanonicalGraphAndRejectsModelOwnedFields() {
    var db = mock(JdbcTemplate.class);
    var json = new ObjectMapper();
    var store = new ContentStore(db, json);
    var revisions = new io.archdeck.backend.service.TopicRevisions(db, store, json);
    var before =
        json.createObjectNode().put("id", "spa").put("title", "Canonical").put("lockVersion", 3);
    before.putArray("diagramNodes").addObject().put("id", "browser").put("label", "Browser");
    before.putArray("codeSnippets").addObject().put("code", "keep this source");
    var target = json.createObjectNode().put("baseLockVersion", 3);
    target.set("topic", before);
    var candidate = json.createObjectNode();
    var patch = json.createObjectNode().put("summary", "Evidence-backed summary");
    revisions.attach(candidate, target, patch);
    assertEquals(before.path("diagramNodes"), candidate.path("proposedTopic").path("diagramNodes"));
    assertEquals(before.path("codeSnippets"), candidate.path("proposedTopic").path("codeSnippets"));
    assertEquals("Canonical", candidate.path("proposedTopic").path("title").asText());
    assertEquals(
        "Evidence-backed summary", candidate.path("proposedTopic").path("summary").asText());
    patch.put("lockVersion", 100);
    assertThrows(ResponseStatusException.class, () -> revisions.attach(candidate, target, patch));
    verifyNoInteractions(db);
  }
}
