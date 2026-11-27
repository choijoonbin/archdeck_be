package io.archdeck.backend.repository;

import io.archdeck.backend.domain.GlossaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GlossaryRepository extends JpaRepository<GlossaryEntity, String> {
    List<GlossaryEntity> findByCategoryIgnoreCase(String category);
}
