package io.archdeck.backend.service;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  public ContentStore(JdbcTemplate d, ObjectMapper j) {
    db = d;
    json = j;
  }

  public JsonNode parse(String s) {
    try {
      return json.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException("invalid_stored_content");
    }
  }

  public String string(JsonNode n) {
    try {
      return json.writeValueAsString(n);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid_content");
    }
  }

  public UUID identity(String legacy) {
    try {
      return UUID.fromString(legacy);
    } catch (Exception e) {
      return UUID.nameUUIDFromBytes(("archdeck:" + legacy).getBytes(StandardCharsets.UTF_8));
    }
  }

  public List<JsonNode> list(String kind, String search, String category) {
    return db
        .query(
            "select"
                + " id,slug,title,subtitle,summary,body_markdown,payload::text,lock_version,published_at"
                + " from archdeck.content_items where kind=? and status='published' order by"
                + " created_at,id",
            (r, n) -> {
              ObjectNode p = (ObjectNode) parse(r.getString("payload"));
              p.put("id", p.path("legacyId").asText(r.getString("id")));
              p.remove("legacyId");
              p.put("slug", r.getString("slug"));
              p.put("lockVersion", r.getLong("lock_version"));
              if (kind.equals("topic")) {
                p.put("title", r.getString("title"));
                p.put("koreanTitle", r.getString("subtitle"));
                p.put("summary", r.getString("summary"));
                p.put("overview", r.getString("body_markdown"));
                p.set("diagramNodes", nodes(r.getString("id")));
                p.set("diagramEdges", edges(r.getString("id")));
                p.set("flowSteps", steps(r.getString("id")));
              } else if (kind.equals("glossary")) {
                p.put("term", r.getString("title"));
                p.put("koreanName", r.getString("subtitle"));
                p.put("shortDef", r.getString("summary"));
                p.put("fullDef", r.getString("body_markdown"));
              } else {
                p.put("title", r.getString("title"));
                if (kind.equals("comparison")) p.put("subtitle", r.getString("subtitle"));
                if (kind.equals("insight")) {
                  p.put("summary", r.getString("summary"));
                  p.put("publishedAt", r.getTimestamp("published_at").toInstant().toString());
                }
              }
              return (JsonNode) p;
            },
            kind)
        .stream()
        .filter(p -> search == null || string(p).toLowerCase().contains(search.toLowerCase()))
        .filter(
            p ->
                category == null
                    || category.equals("ALL")
                    || p.path("category").asText().equals(category))
        .toList();
  }

  private ArrayNode nodes(String id) {
    ArrayNode a = json.createArrayNode();
    db.queryForList(
            "select * from archdeck.flow_nodes where content_id=?::uuid order by position", id)
        .forEach(
            r -> {
              var n = json.createObjectNode();
              n.put("id", r.get("node_key").toString());
              n.put("type", r.get("node_type").toString());
              n.put("label", r.get("label").toString());
              n.put("subLabel", r.get("sub_label").toString());
              n.put("icon", r.get("icon_key").toString());
              n.put("trustZone", r.get("trust_zone").toString());
              if (r.get("x") != null) n.put("x", ((Number) r.get("x")).doubleValue());
              if (r.get("y") != null) n.put("y", ((Number) r.get("y")).doubleValue());
              a.add(n);
            });
    return a;
  }

  private ArrayNode edges(String id) {
    ArrayNode a = json.createArrayNode();
    db.queryForList(
            "select * from archdeck.flow_edges where content_id=?::uuid order by position", id)
        .forEach(
            r -> {
              ObjectNode n = a.addObject();
              n.put("id", r.get("edge_key").toString());
              n.put("source", r.get("source_node_key").toString());
              n.put("target", r.get("target_node_key").toString());
              n.put("label", r.get("label").toString());
              n.put("protocol", r.get("protocol").toString());
              n.put("type", r.get("edge_type").toString());
              n.put("trustBoundary", r.get("trust_boundary").toString());
            });
    return a;
  }

  private ArrayNode steps(String id) {
    ArrayNode a = json.createArrayNode();
    db.queryForList(
            "select * from archdeck.flow_steps where content_id=?::uuid order by position", id)
        .forEach(
            r -> {
              var n = json.createObjectNode();
              n.put("step", ((Number) r.get("position")).intValue());
              n.put("title", r.get("title").toString());
              n.put("action", r.get("action").toString());
              n.put("description", r.get("description").toString());
              n.put("payloadExample", r.get("payload_example").toString());
              n.put("securityNote", r.get("security_note").toString());
              n.put("actor", r.get("actor").toString());
              n.put("target", r.get("target").toString());
              n.put("protocol", r.get("protocol").toString());
              ArrayNode active = json.createArrayNode();
              db.queryForList(
                      "select node_key from archdeck.flow_step_nodes where content_id=?::uuid and"
                          + " step_key=? order by node_key",
                      id,
                      r.get("step_key"))
                  .forEach(v -> active.add(v.get("node_key").toString()));
              n.set("activeNodes", active);
              ArrayNode activeEdges = json.createArrayNode();
              db.queryForList(
                      "select edge_key from archdeck.flow_step_edges where content_id=?::uuid and"
                          + " step_key=? order by position",
                      id,
                      r.get("step_key"))
                  .forEach(v -> activeEdges.add(v.get("edge_key").toString()));
              n.set("activeEdgeIds", activeEdges);
              a.add(n);
            });
    return a;
  }

  public JsonNode get(String kind, String id) {
    return list(kind, null, null).stream()
        .filter(p -> p.path("id").asText().equals(id) || p.path("slug").asText().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
  }

  @Transactional
  public JsonNode save(String kind, ObjectNode input, String existing, String author) {
    validate(kind, input);
    if (author != null)
      for (String key : List.of("relatedTermIds", "relatedTopicIds", "securityChecklistIds"))
        for (JsonNode ref : input.path(key)) {
          Integer count =
              db.queryForObject(
                  "select count(*) from archdeck.content_items where id=? and status='published'",
                  Integer.class,
                  identity(ref.asText()));
          if (count == null || count == 0)
            throw new ResponseStatusException(BAD_REQUEST, "unknown_content_reference");
        }
    String legacy =
        existing == null ? input.path("id").asText(UUID.randomUUID().toString()) : existing;
    UUID id = identity(legacy);
    String title = input.path(kind.equals("glossary") ? "term" : "title").asText();
    if (title.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "title_required");
    String slug =
        input
            .path("slug")
            .asText(legacy)
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    if (slug.isBlank()) slug = "content-" + id;
    long version =
        db.queryForList("select lock_version from archdeck.content_items where id=?", id).stream()
            .mapToLong(x -> ((Number) x.get("lock_version")).longValue())
            .findFirst()
            .orElse(-1);
    if (existing == null && version >= 0)
      throw new ResponseStatusException(CONFLICT, "content_exists");
    if (existing != null && version < 0) throw new ResponseStatusException(NOT_FOUND);
    if (version >= 0) {
      db.queryForList("select id from archdeck.content_items where id=? for update", id);
      String storedKind =
          db.queryForObject("select kind from archdeck.content_items where id=?", String.class, id);
      if (!kind.equals(storedKind))
        throw new ResponseStatusException(CONFLICT, "content_kind_conflict");
      long latest =
          db.queryForObject(
              "select lock_version from archdeck.content_items where id=?", Long.class, id);
      if (latest != version) throw new ResponseStatusException(CONFLICT, "revision_conflict");
    }
    if (existing != null && input.path("lockVersion").asLong(-2) != version)
      throw new ResponseStatusException(CONFLICT, "revision_conflict");
    ObjectNode payload = input.deepCopy();
    for (String key :
        List.of(
            "id",
            "slug",
            "title",
            "subtitle",
            "summary",
            "overview",
            "term",
            "koreanTitle",
            "koreanName",
            "shortDef",
            "fullDef",
            "publishedAt",
            "lockVersion",
            "diagramNodes",
            "diagramEdges",
            "flowSteps")) payload.remove(key);
    payload.put("legacyId", legacy);
    String category = input.path("category").asText("IAM & Auth");
    String catSlug =
        switch (category) {
          case "Frontend Architecture" -> "frontend-architecture";
          case "Backend & BFF" -> "backend-and-integration";
          case "Security & Defense", "XSS Defense", "CSRF Defense", "Security Attack & Defense" ->
              "security-and-operations";
          default -> "identity-and-access";
        };
    UUID cat =
        db.queryForObject("select id from archdeck.categories where slug=?", UUID.class, catSlug);
    String subtitle =
        input
            .path(
                kind.equals("topic")
                    ? "koreanTitle"
                    : kind.equals("glossary") ? "koreanName" : "subtitle")
            .asText();
    String summary = input.path(kind.equals("glossary") ? "shortDef" : "summary").asText();
    String body = input.path(kind.equals("glossary") ? "fullDef" : "overview").asText();
    db.update(
        "insert into"
            + " archdeck.content_items(id,kind,slug,title,subtitle,summary,body_markdown,category_id,status,payload,reviewed_at,published_at,lock_version)values(?,?,?,?,?,?,?,?,'published',?::jsonb,current_date,now(),?)"
            + " on conflict(id) do update set"
            + " title=excluded.title,subtitle=excluded.subtitle,summary=excluded.summary,body_markdown=excluded.body_markdown,payload=excluded.payload,category_id=excluded.category_id,slug=excluded.slug,lock_version=excluded.lock_version",
        id,
        kind,
        slug,
        title,
        subtitle,
        summary,
        body,
        cat,
        string(payload),
        version + 1);
    if (kind.equals("topic")) {
      db.update("delete from archdeck.flow_steps where content_id=?", id);
      db.update("delete from archdeck.flow_edges where content_id=?", id);
      db.update("delete from archdeck.flow_nodes where content_id=?", id);
      int i = 0;
      for (JsonNode node : input.path("diagramNodes")) {
        db.update(
            "insert into"
                + " archdeck.flow_nodes(content_id,node_key,node_type,label,sub_label,icon_key,position,x,y,trust_zone)values(?,?,?,?,?,?,?,?,?,?)",
            id,
            node.path("id").asText(),
            node.path("type").asText("server"),
            node.path("label").asText(),
            node.path("subLabel").asText(),
            node.path("icon").asText("Server"),
            i++,
            node.hasNonNull("x") ? node.get("x").asDouble() : null,
            node.hasNonNull("y") ? node.get("y").asDouble() : null,
            node.path("trustZone").asText());
      }
      i = 0;
      for (JsonNode edge : input.path("diagramEdges"))
        db.update(
            "insert into"
                + " archdeck.flow_edges(content_id,edge_key,source_node_key,target_node_key,label,protocol,edge_type,position,trust_boundary)"
                + " values(?,?,?,?,?,?,?,?,?)",
            id,
            edge.path("id").asText(),
            edge.path("source").asText(),
            edge.path("target").asText(),
            edge.path("label").asText(),
            edge.path("protocol").asText(),
            edge.path("type").asText("request"),
            i++,
            edge.path("trustBoundary").asText());
      int position = 1;
      for (JsonNode step : input.path("flowSteps")) {
        String key = "step" + position;
        db.update(
            "insert into"
                + " archdeck.flow_steps(content_id,step_key,position,title,action,description,payload_example,security_note,actor,target,protocol)values(?,?,?,?,?,?,?,?,?,?,?)",
            id,
            key,
            position++,
            step.path("title").asText(),
            step.path("action").asText(),
            step.path("description").asText(),
            step.path("payloadExample").asText(),
            step.path("securityNote").asText(),
            step.path("actor").asText(),
            step.path("target").asText(),
            step.path("protocol").asText());
        for (JsonNode active : step.path("activeNodes"))
          db.update(
              "insert into archdeck.flow_step_nodes(content_id,step_key,node_key)values(?,?,?)",
              id,
              key,
              active.asText());
        int edgePosition = 0;
        for (JsonNode edge : step.path("activeEdgeIds"))
          db.update(
              "insert into archdeck.flow_step_edges(content_id,step_key,edge_key,position)"
                  + " values(?,?,?,?)",
              id,
              key,
              edge.asText(),
              edgePosition++);
      }
    }
    db.update(
        "insert into"
            + " archdeck.content_revisions(content_id,revision_no,schema_version,snapshot,change_note,created_by)values(?,?,1,?::jsonb,'content"
            + " saved',?::uuid)",
        id,
        version + 2,
        string(input),
        author);
    return get(kind, legacy);
  }

  private void validate(String kind, ObjectNode input) {
    List<String> arrays =
        switch (kind) {
          case "topic" ->
              List.of(
                  "badges",
                  "diagramNodes",
                  "diagramEdges",
                  "flowSteps",
                  "keyTakeaways",
                  "codeSnippets",
                  "securityChecklistIds",
                  "relatedTermIds",
                  "relatedTopicIds");
          case "glossary" -> List.of("keyPoints", "tags");
          case "comparison" -> List.of("subjects", "dimensions", "decisionGuide");
          default -> List.of();
        };
    for (String key : arrays) {
      if (!input.has(key)) input.set(key, json.createArrayNode());
      else if (!input.get(key).isArray())
        throw new ResponseStatusException(BAD_REQUEST, key + "_must_be_array");
    }
    if (kind.equals("topic")) {
      if (!input.has("tradeOffs"))
        input.set("tradeOffs", json.createObjectNode().set("pros", json.createArrayNode()));
      if (!input.path("tradeOffs").isObject())
        throw new ResponseStatusException(BAD_REQUEST, "tradeoffs_required");
      ObjectNode trade = (ObjectNode) input.get("tradeOffs");
      for (String key : List.of("pros", "cons")) {
        if (!trade.has(key)) trade.set(key, json.createArrayNode());
        else if (!trade.path(key).isArray())
          throw new ResponseStatusException(BAD_REQUEST, "tradeoffs_array_required");
      }
      Set<String> nodes = new HashSet<>();
      for (JsonNode node : input.path("diagramNodes")) {
        if (node.path("id").asText().isBlank()
            || !nodes.add(node.path("id").asText())
            || node.path("label").asText().isBlank())
          throw new ResponseStatusException(BAD_REQUEST, "invalid_graph_node");
        for (String coordinate : List.of("x", "y"))
          if (node.hasNonNull(coordinate)
              && (!node.path(coordinate).isNumber()
                  || !Double.isFinite(node.path(coordinate).asDouble())
                  || Math.abs(node.path(coordinate).asDouble()) > 99999999))
            throw new ResponseStatusException(BAD_REQUEST, "invalid_graph_coordinate");
      }
      Set<String> edges = new HashSet<>();
      for (JsonNode edge : input.path("diagramEdges")) {
        if (edge.path("id").asText().isBlank()
            || !edges.add(edge.path("id").asText())
            || !nodes.contains(edge.path("source").asText())
            || !nodes.contains(edge.path("target").asText())
            || !List.of("request", "response", "event", "internal", "error")
                .contains(edge.path("type").asText("request")))
          throw new ResponseStatusException(BAD_REQUEST, "invalid_graph_edge");
      }
      for (JsonNode step : input.path("flowSteps")) {
        if (step.path("title").asText().isBlank())
          throw new ResponseStatusException(BAD_REQUEST, "step_title_required");
        for (String field : List.of("activeNodes", "activeEdgeIds")) {
          if (step.has(field) && !step.path(field).isArray())
            throw new ResponseStatusException(BAD_REQUEST, "invalid_graph_highlight");
          Set<String> unique = new HashSet<>();
          for (JsonNode active : step.path(field))
            if (!(field.equals("activeNodes") ? nodes : edges).contains(active.asText())
                || !unique.add(active.asText()))
              throw new ResponseStatusException(BAD_REQUEST, "unknown_graph_reference");
        }
      }
    }
  }

  @Transactional
  public void archive(String kind, String legacy, Long expected, String author) {
    UUID id = identity(legacy);
    var rows =
        db.queryForList(
            "select lock_version from archdeck.content_items where id=? and kind=? and"
                + " status='published' for update",
            id,
            kind);
    if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
    long version = ((Number) rows.getFirst().get("lock_version")).longValue();
    if (expected != null && expected != version)
      throw new ResponseStatusException(CONFLICT, "revision_conflict");
    JsonNode snapshot = get(kind, legacy);
    db.update(
        "insert into"
            + " archdeck.content_revisions(content_id,revision_no,schema_version,snapshot,change_note,created_by)values(?,?,1,?::jsonb,'content"
            + " archived',?::uuid)",
        id,
        version + 2,
        string(snapshot),
        author);
    db.update(
        "update archdeck.content_items set status='archived',lock_version=lock_version+1 where id=?"
            + " and lock_version=?",
        id,
        version);
  }
}
