package io.archdeck.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "ArchDeck Spring Boot API Server",
                "version", "1.0.0",
                "framework", "Spring Boot 3.4.2",
                "javaVersion", System.getProperty("java.version"),
                "timestamp", Instant.now().toString()
        ));
    }
}
