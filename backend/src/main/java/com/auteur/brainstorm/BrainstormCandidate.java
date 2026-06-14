package com.auteur.brainstorm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrainstormCandidate {
    private String title;
    private String dynasty;
    private String genre;
    private String protagonist;

    @JsonProperty("hook_type")
    private String hookType;

    private String emotion;

    @JsonProperty("duration_minutes")
    private Integer durationMinutes;

    @JsonProperty("potential_score")
    private BigDecimal potentialScore;

    @JsonProperty("historical_reference")
    private String historicalReference;

    @JsonProperty("suggested_series")
    private String suggestedSeries;

    /** lifecopy 老路径:输出 identity_tag/era/archetype/nodes 4 个 hardcode 字段。 */
    @JsonProperty("identity_tag")
    private String identityTag;

    private String era;

    private String archetype;

    private List<NodeCandidate> nodes;

    /**
     * 通用路径(2026-06 加):brainstorm.yaml 直接按预设的 input_schema 输出对应字段对象,
     * BrainstormService 整个塞进 topic.preset_input_json。
     * 各预设的 brainstorm prompt 自己保证字段名跟 input_schema 一致(由预设作者维护)。
     * 优先级:preset_input 非空 → 用它;否则回落 identity_tag/era/nodes 老路径(向下兼容)。
     */
    @JsonProperty("preset_input")
    private Map<String, Object> presetInput;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodeCandidate {
        private String phase;
        private String scene;
        private List<String> details;
    }
}

