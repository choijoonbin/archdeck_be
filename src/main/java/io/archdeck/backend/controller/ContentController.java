package io.archdeck.backend.controller;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archdeck.backend.service.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ContentController {
  private final ContentStore store;

  public ContentController(ContentStore s) {
    store = s;
  }

  private String kind(String name) {
    return switch (name) {
      case "topics" -> "topic";
      case "glossary" -> "glossary";
      case "comparisons" -> "comparison";
      case "checklists" -> "checklist";
      case "insights" -> "insight";
      default ->
          throw new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.NOT_FOUND);
    };
  }

  @GetMapping("/api/{collection:topics|glossary|comparisons|checklists|insights}")
  List<JsonNode> list(
      @PathVariable String collection,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String category) {
    return store.list(kind(collection), search, category);
  }

  @GetMapping("/api/{collection:topics|glossary|comparisons|checklists|insights}/{id}")
  JsonNode get(@PathVariable String collection, @PathVariable String id) {
    return store.get(kind(collection), id);
  }

  @PostMapping("/api/{collection:topics|glossary|comparisons|checklists}")
  JsonNode create(
      @PathVariable String collection,
      @RequestBody ObjectNode b,
      @AuthenticationPrincipal Accounts.Principal p) {
    return store.save(kind(collection), b, null, p.id());
  }

  @PutMapping("/api/{collection:topics|glossary|comparisons|checklists}/{id}")
  JsonNode update(
      @PathVariable String collection,
      @PathVariable String id,
      @RequestBody ObjectNode b,
      @AuthenticationPrincipal Accounts.Principal p) {
    return store.save(kind(collection), b, id, p.id());
  }

  @DeleteMapping("/api/{collection:topics|glossary|comparisons|checklists}/{id}")
  void archive(
      @PathVariable String collection,
      @PathVariable String id,
      @RequestParam(required = false) Long lockVersion,
      @AuthenticationPrincipal Accounts.Principal p) {
    store.archive(kind(collection), id, lockVersion, p.id());
  }
}
