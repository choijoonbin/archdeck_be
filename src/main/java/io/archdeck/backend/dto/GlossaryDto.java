package io.archdeck.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GlossaryDto {
  private String id;
  private String term;
  private String koreanName;
  private String category;
  private String shortDef;
  private String fullDef;
  private List<String> keyPoints;
  private String specRef;
  private String securityRelevance;
  private List<String> tags;
  private List<String> relatedTopicIds;
}
