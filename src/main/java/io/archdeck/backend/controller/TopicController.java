package io.archdeck.backend.controller;

import io.archdeck.backend.dto.TopicDto;
import io.archdeck.backend.service.ArchDeckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/topics")
@RequiredArgsConstructor
public class TopicController {

    private final ArchDeckService archDeckService;

    @GetMapping
    public ResponseEntity<List<TopicDto>> getAllTopics(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(archDeckService.getAllTopics(category, search));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTopicById(@PathVariable String id) {
        return archDeckService.getTopicById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<TopicDto> createTopic(@RequestBody TopicDto topicDto) {
        TopicDto saved = archDeckService.saveTopic(topicDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
