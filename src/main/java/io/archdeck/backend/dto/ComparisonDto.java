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
public class ComparisonDto {
    private String id;
    private String title;
    private String subtitle;
    private String category;
    private List<SubjectDto> subjects;
    private List<DimensionDto> dimensions;
    private List<DecisionGuideRuleDto> decisionGuide;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubjectDto {
        private String id;
        private String name;
        private String tag;
        private String iconName;
        private String recommendedFor;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionValueDto {
        private String text;
        private String status;
        private String detail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionDto {
        private String key;
        private String name;
        private String description;
        private Map<String, DimensionValueDto> values;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionGuideRuleDto {
        private String condition;
        private String recommendation;
        private String reason;
    }
}
