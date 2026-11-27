package io.archdeck.backend.repository;

import io.archdeck.backend.domain.ComparisonEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ComparisonRepository extends JpaRepository<ComparisonEntity, String> {
}
