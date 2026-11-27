package io.archdeck.backend.repository;

import io.archdeck.backend.domain.ChecklistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChecklistRepository extends JpaRepository<ChecklistEntity, String> {
    List<ChecklistEntity> findByCategoryIgnoreCase(String category);
}
