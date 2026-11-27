package io.archdeck.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "glossary_terms")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlossaryEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String term;

    @Column(nullable = false, length = 255)
    private String koreanName;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String shortDef;

    @Column(columnDefinition = "TEXT")
    private String fullDef;

    private String specRef;

    @Column(columnDefinition = "TEXT")
    private String securityRelevance;

    @Column(columnDefinition = "TEXT")
    private String payloadJson;
}
