package io.archdeck.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_learning")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLearningEntity {

    @Id
    @Column(length = 64)
    private String userId;

    @Column(columnDefinition = "TEXT")
    private String payloadJson;
}
