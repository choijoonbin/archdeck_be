package io.archdeck.backend.controller;

import io.archdeck.backend.dto.ComparisonDto;
import io.archdeck.backend.service.ArchDeckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/comparisons")
@RequiredArgsConstructor
public class ComparisonController {

    private final ArchDeckService archDeckService;

    @GetMapping
    public ResponseEntity<List<ComparisonDto>> getAllComparisons() {
        return ResponseEntity.ok(archDeckService.getAllComparisons());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getComparisonById(@PathVariable String id) {
        return archDeckService.getComparisonById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
