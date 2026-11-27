package io.archdeck.backend.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "topics")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String slug;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 255)
    private String koreanTitle;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String overview;

    private int readingTimeMinutes;

    // Full JSON payload storage for zero-loss serialization
    @Column(columnDefinition = "TEXT")
    private String payloadJson;
}
