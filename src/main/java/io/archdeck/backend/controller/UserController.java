package io.archdeck.backend.controller;

import io.archdeck.backend.dto.UserLearningDto;
import io.archdeck.backend.service.ArchDeckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final ArchDeckService archDeckService;

    @GetMapping("/learning")
    public ResponseEntity<UserLearningDto> getUserLearning() {
        return ResponseEntity.ok(archDeckService.getUserLearning());
    }

    @PostMapping("/learning")
    public ResponseEntity<UserLearningDto> saveUserLearning(@RequestBody UserLearningDto dto) {
        return ResponseEntity.ok(archDeckService.saveUserLearning(dto));
    }

    @PostMapping("/bookmarks")
    public ResponseEntity<Map<String, Object>> toggleBookmark(@RequestBody Map<String, String> body) {
        String topicId = body.get("topicId");
        if (topicId == null || topicId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(archDeckService.toggleBookmark(topicId));
    }

    @PostMapping("/progress")
    public ResponseEntity<Map<String, Object>> toggleProgress(@RequestBody Map<String, String> body) {
        String topicId = body.get("topicId");
        if (topicId == null || topicId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(archDeckService.toggleProgress(topicId));
    }

    @PostMapping("/notes")
    public ResponseEntity<Map<String, Object>> saveNote(@RequestBody Map<String, String> body) {
        String topicId = body.get("topicId");
        String note = body.get("note");
        if (topicId == null || topicId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(archDeckService.saveNote(topicId, note));
    }
}
