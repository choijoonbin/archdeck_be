package io.archdeck.backend.controller;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> integrity() {
    return ResponseEntity.status(409).body(Map.of("error", "content_or_account_constraint"));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> bad() {
    return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
  }
}
