package io.archdeck.backend.controller;

import io.archdeck.backend.dto.GlossaryDto;
import io.archdeck.backend.service.ArchDeckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/glossary")
@RequiredArgsConstructor
public class GlossaryController {

    private final ArchDeckService archDeckService;

    @GetMapping
    public ResponseEntity<List<GlossaryDto>> getAllGlossary(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(archDeckService.getAllGlossary(category, search));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getGlossaryById(@PathVariable String id) {
        return archDeckService.getGlossaryById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<GlossaryDto> createGlossary(@RequestBody GlossaryDto glossaryDto) {
        GlossaryDto saved = archDeckService.saveGlossary(glossaryDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
