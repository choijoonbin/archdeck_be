package io.archdeck.backend.controller;

import io.archdeck.backend.dto.ChecklistDto;
import io.archdeck.backend.service.ArchDeckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/checklists")
@RequiredArgsConstructor
public class ChecklistController {

    private final ArchDeckService archDeckService;

    @GetMapping
    public ResponseEntity<List<ChecklistDto>> getAllChecklists(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String priority) {
        return ResponseEntity.ok(archDeckService.getAllChecklists(category, priority));
    }
}
