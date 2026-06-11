package com.auteur.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Component
public class PromptTemplateLoader {

    private final ResourceLoader resourceLoader;
    private final String location;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final Map<String, PromptYaml> cache = new HashMap<>();

    public PromptTemplateLoader(ResourceLoader resourceLoader,
                                @Value("${auteur.prompts.location:classpath:prompts/}") String location) {
        this.resourceLoader = resourceLoader;
        this.location = location.endsWith("/") ? location : location + "/";
    }

    public PromptYaml load(String name) {
        return cache.computeIfAbsent(name, this::doLoad);
    }

    private PromptYaml doLoad(String name) {
        Resource res = resourceLoader.getResource(location + name + ".yaml");
        if (!res.exists()) {
            throw new IllegalArgumentException("Prompt template not found: " + name);
        }
        try (InputStream in = res.getInputStream()) {
            return yaml.readValue(in, PromptYaml.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt template: " + name, e);
        }
    }

    public static String render(String template, Map<String, ?> params) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, ?> e : params.entrySet()) {
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            out = out.replace("{{" + e.getKey() + "}}", value);
        }
        return out;
    }

    /** YAML 文件反序列化容器。 */
    @Data
    public static class PromptYaml {
        private String name;
        private String description;
        private String model;
        private Double temperature;
        /** 输出 token 上限。可空 = 不下发，让网关用默认值。 */
        @JsonProperty("max_tokens")
        private Integer maxTokens;
        private String system;
        private String user;
        /** 可选模型路由策略；为空时直接用 {@link #model}。 */
        private Routing routing;
    }

    /**
     * 模型路由策略。`by` 决定按哪个变量打分，目前只识别 `potential_score`。
     * 当变量值 ≥ threshold → premium_model，否则 batch_model。任一字段缺失即回退到顶层 model。
     */
    @Data
    public static class Routing {
        private String by;
        private java.math.BigDecimal threshold;
        @JsonProperty("premium_model")
        private String premiumModel;
        @JsonProperty("batch_model")
        private String batchModel;
    }
}
