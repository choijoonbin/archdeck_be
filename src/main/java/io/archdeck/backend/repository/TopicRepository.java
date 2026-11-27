package io.archdeck.backend.repository;

import io.archdeck.backend.domain.TopicEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TopicRepository extends JpaRepository<TopicEntity, String> {
    Optional<TopicEntity> findBySlug(String slug);
    List<TopicEntity> findByCategoryIgnoreCase(String category);
}
