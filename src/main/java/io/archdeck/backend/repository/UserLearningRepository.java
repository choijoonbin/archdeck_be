package io.archdeck.backend.repository;

import io.archdeck.backend.domain.UserLearningEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserLearningRepository extends JpaRepository<UserLearningEntity, String> {
}
