package io.archdeck.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserLearningDto {
    private List<String> bookmarks;
    private List<String> completedTopics;
    private Map<String, String> notes;
    private Map<String, Boolean> checklistProgress;
}
