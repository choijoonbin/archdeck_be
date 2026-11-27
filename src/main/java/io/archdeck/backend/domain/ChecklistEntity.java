package io.archdeck.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "checklists")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChecklistEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 32)
    private String priority;

    @Column(columnDefinition = "TEXT")
    private String verificationMethod;

    @Column(columnDefinition = "TEXT")
    private String codeExample;

    @Column(columnDefinition = "TEXT")
    private String payloadJson;
}
