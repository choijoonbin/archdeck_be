package io.archdeck.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.archdeck.backend.domain.*;
import io.archdeck.backend.dto.*;
import io.archdeck.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchDeckService {

    private final TopicRepository topicRepository;
    private final GlossaryRepository glossaryRepository;
    private final ComparisonRepository comparisonRepository;
    private final ChecklistRepository checklistRepository;
    private final UserLearningRepository userLearningRepository;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_USER_ID = "default_user";

    // --- Topics ---
    @Transactional(readOnly = true)
    public List<TopicDto> getAllTopics(String category, String search) {
        List<TopicEntity> entities;
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            entities = topicRepository.findByCategoryIgnoreCase(category);
        } else {
            entities = topicRepository.findAll();
        }

        List<TopicDto> dtos = entities.stream()
                .map(this::convertTopicToDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            return dtos.stream().filter(t ->
                    (t.getTitle() != null && t.getTitle().toLowerCase().contains(q)) ||
                    (t.getKoreanTitle() != null && t.getKoreanTitle().toLowerCase().contains(q)) ||
                    (t.getSummary() != null && t.getSummary().toLowerCase().contains(q)) ||
                    (t.getBadges() != null && t.getBadges().stream().anyMatch(b -> b.toLowerCase().contains(q)))
            ).collect(Collectors.toList());
        }

        return dtos;
    }

    @Transactional(readOnly = true)
    public Optional<TopicDto> getTopicById(String id) {
        Optional<TopicEntity> entity = topicRepository.findById(id);
        if (entity.isEmpty()) {
            entity = topicRepository.findBySlug(id);
        }
        return entity.map(this::convertTopicToDto);
    }

    @Transactional
    public TopicDto saveTopic(TopicDto dto) {
        if (dto.getId() == null || dto.getId().isBlank()) {
            dto.setId("topic-" + System.currentTimeMillis());
        }
        if (dto.getSlug() == null || dto.getSlug().isBlank()) {
            dto.setSlug(dto.getTitle().toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        }

        String json = serialize(dto);
        TopicEntity entity = TopicEntity.builder()
                .id(dto.getId())
                .slug(dto.getSlug())
                .title(dto.getTitle())
                .koreanTitle(dto.getKoreanTitle())
                .category(dto.getCategory() != null ? dto.getCategory() : "Frontend Architecture")
                .summary(dto.getSummary())
                .overview(dto.getOverview())
                .readingTimeMinutes(dto.getReadingTimeMinutes() > 0 ? dto.getReadingTimeMinutes() : 5)
                .payloadJson(json)
                .build();

        topicRepository.save(entity);
        return dto;
    }

    // --- Glossary ---
    @Transactional(readOnly = true)
    public List<GlossaryDto> getAllGlossary(String category, String search) {
        List<GlossaryEntity> entities;
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            entities = glossaryRepository.findByCategoryIgnoreCase(category);
        } else {
            entities = glossaryRepository.findAll();
        }

        List<GlossaryDto> dtos = entities.stream()
                .map(this::convertGlossaryToDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            return dtos.stream().filter(g ->
                    (g.getTerm() != null && g.getTerm().toLowerCase().contains(q)) ||
                    (g.getKoreanName() != null && g.getKoreanName().toLowerCase().contains(q)) ||
                    (g.getShortDef() != null && g.getShortDef().toLowerCase().contains(q)) ||
                    (g.getTags() != null && g.getTags().stream().anyMatch(t -> t.toLowerCase().contains(q)))
            ).collect(Collectors.toList());
        }

        return dtos;
    }

    @Transactional(readOnly = true)
    public Optional<GlossaryDto> getGlossaryById(String id) {
        return glossaryRepository.findById(id).map(this::convertGlossaryToDto);
    }

    @Transactional
    public GlossaryDto saveGlossary(GlossaryDto dto) {
        if (dto.getId() == null || dto.getId().isBlank()) {
            dto.setId("term-" + System.currentTimeMillis());
        }

        String json = serialize(dto);
        GlossaryEntity entity = GlossaryEntity.builder()
                .id(dto.getId())
                .term(dto.getTerm())
                .koreanName(dto.getKoreanName())
                .category(dto.getCategory() != null ? dto.getCategory() : "Protocol & Spec")
                .shortDef(dto.getShortDef())
                .fullDef(dto.getFullDef())
                .specRef(dto.getSpecRef())
                .securityRelevance(dto.getSecurityRelevance())
                .payloadJson(json)
                .build();

        glossaryRepository.save(entity);
        return dto;
    }

    // --- Comparisons ---
    @Transactional(readOnly = true)
    public List<ComparisonDto> getAllComparisons() {
        return comparisonRepository.findAll().stream()
                .map(this::convertComparisonToDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<ComparisonDto> getComparisonById(String id) {
        return comparisonRepository.findById(id).map(this::convertComparisonToDto);
    }

    @Transactional
    public ComparisonDto saveComparison(ComparisonDto dto) {
        String json = serialize(dto);
        ComparisonEntity entity = ComparisonEntity.builder()
                .id(dto.getId())
                .title(dto.getTitle())
                .subtitle(dto.getSubtitle())
                .category(dto.getCategory())
                .payloadJson(json)
                .build();
        comparisonRepository.save(entity);
        return dto;
    }

    // --- Checklists ---
    @Transactional(readOnly = true)
    public List<ChecklistDto> getAllChecklists(String category, String priority) {
        List<ChecklistEntity> entities;
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            entities = checklistRepository.findByCategoryIgnoreCase(category);
        } else {
            entities = checklistRepository.findAll();
        }

        List<ChecklistDto> dtos = entities.stream()
                .map(this::convertChecklistToDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (priority != null && !priority.isBlank() && !"ALL".equalsIgnoreCase(priority)) {
            return dtos.stream()
                    .filter(c -> priority.equalsIgnoreCase(c.getPriority()))
                    .collect(Collectors.toList());
        }

        return dtos;
    }

    @Transactional
    public ChecklistDto saveChecklist(ChecklistDto dto) {
        String json = serialize(dto);
        ChecklistEntity entity = ChecklistEntity.builder()
                .id(dto.getId())
                .category(dto.getCategory())
                .title(dto.getTitle())
                .description(dto.getDescription())
                .priority(dto.getPriority())
                .verificationMethod(dto.getVerificationMethod())
                .codeExample(dto.getCodeExample())
                .payloadJson(json)
                .build();
        checklistRepository.save(entity);
        return dto;
    }

    // --- User Learning ---
    @Transactional(readOnly = true)
    public UserLearningDto getUserLearning() {
        return userLearningRepository.findById(DEFAULT_USER_ID)
                .map(e -> deserialize(e.getPayloadJson(), UserLearningDto.class))
                .orElseGet(() -> UserLearningDto.builder()
                        .bookmarks(new ArrayList<>())
                        .completedTopics(new ArrayList<>())
                        .notes(new HashMap<>())
                        .checklistProgress(new HashMap<>())
                        .build());
    }

    @Transactional
    public UserLearningDto saveUserLearning(UserLearningDto dto) {
        String json = serialize(dto);
        UserLearningEntity entity = UserLearningEntity.builder()
                .userId(DEFAULT_USER_ID)
                .payloadJson(json)
                .build();
        userLearningRepository.save(entity);
        return dto;
    }

    @Transactional
    public Map<String, Object> toggleBookmark(String topicId) {
        UserLearningDto state = getUserLearning();
        List<String> bookmarks = state.getBookmarks() != null ? new ArrayList<>(state.getBookmarks()) : new ArrayList<>();
        if (bookmarks.contains(topicId)) {
            bookmarks.remove(topicId);
        } else {
            bookmarks.add(topicId);
        }
        state.setBookmarks(bookmarks);
        saveUserLearning(state);
        return Map.of("bookmarks", bookmarks);
    }

    @Transactional
    public Map<String, Object> toggleProgress(String topicId) {
        UserLearningDto state = getUserLearning();
        List<String> completed = state.getCompletedTopics() != null ? new ArrayList<>(state.getCompletedTopics()) : new ArrayList<>();
        if (completed.contains(topicId)) {
            completed.remove(topicId);
        } else {
            completed.add(topicId);
        }
        state.setCompletedTopics(completed);
        saveUserLearning(state);
        return Map.of("completedTopics", completed);
    }

    @Transactional
    public Map<String, Object> saveNote(String topicId, String note) {
        UserLearningDto state = getUserLearning();
        Map<String, String> notes = state.getNotes() != null ? new HashMap<>(state.getNotes()) : new HashMap<>();
        notes.put(topicId, note != null ? note : "");
        state.setNotes(notes);
        saveUserLearning(state);
        return Map.of("notes", notes);
    }

    // --- Helpers ---
    private TopicDto convertTopicToDto(TopicEntity entity) {
        return deserialize(entity.getPayloadJson(), TopicDto.class);
    }

    private GlossaryDto convertGlossaryToDto(GlossaryEntity entity) {
        return deserialize(entity.getPayloadJson(), GlossaryDto.class);
    }

    private ComparisonDto convertComparisonToDto(ComparisonEntity entity) {
        return deserialize(entity.getPayloadJson(), ComparisonDto.class);
    }

    private ChecklistDto convertChecklistToDto(ChecklistEntity entity) {
        return deserialize(entity.getPayloadJson(), ChecklistDto.class);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON serialization failed", e);
            throw new RuntimeException("직렬화 실패", e);
        }
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("JSON deserialization failed", e);
            return null;
        }
    }
}
