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
public class ChecklistDto {
  private String id;
  private String category;
  private String title;
  private String description;
  private String priority;
  private String verificationMethod;
  private String codeExample;
  private List<String> relatedTermIds;
}
