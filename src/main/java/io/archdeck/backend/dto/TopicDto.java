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
public class TopicDto {
    private String id;
    private String slug;
    private String title;
    private String koreanTitle;
    private String category;
    private String summary;
    private String overview;
    private List<String> badges;
    private int readingTimeMinutes;
    private List<DiagramNodeDto> diagramNodes;
    private List<FlowStepDto> flowSteps;
    private List<String> keyTakeaways;
    private TradeOffsDto tradeOffs;
    private List<CodeSnippetDto> codeSnippets;
    private List<String> securityChecklistIds;
    private List<String> relatedTermIds;
    private List<String> relatedTopicIds;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiagramNodeDto {
        private String id;
        private String label;
        private String subLabel;
        private String type;
        private String icon;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowStepDto {
        private int step;
        private String title;
        private String actor;
        private String target;
        private String protocol;
        private String action;
        private String description;
        private String payloadExample;
        private String securityNote;
        private List<String> activeNodes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeOffsDto {
        private List<String> pros;
        private List<String> cons;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeSnippetDto {
        private String id;
        private String title;
        private String filename;
        private String language;
        private String code;
        private String description;
    }
}
