package io.archdeck.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "comparisons")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComparisonEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 255)
    private String subtitle;

    @Column(length = 64)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String payloadJson;
}
