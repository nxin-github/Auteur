package com.auteur.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 渲染好的 system/user prompt。配置可来自 classpath:prompts/*.yaml(老路径,category 参数)
 * 或来自 DB 的 yaml 字符串(V51 新路径,renderInline)。Rendered.sourceId 永远 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private final PromptTemplateLoader yamlLoader;
    /** 用于把 DB 里的 yaml 字符串解析成 PromptYaml。 */
    private static final ObjectMapper INLINE_YAML = new ObjectMapper(new YAMLFactory());

    public record Rendered(
            String system,
            String user,
            String model,
            Double temperature,
            Integer maxTokens,
            Long sourceId,
            PromptTemplateLoader.Routing routing
    ) {}

    public Rendered render(String category, Map<String, ?> params) {
        PromptTemplateLoader.PromptYaml yaml = yamlLoader.load(category);
        if (yaml == null) {
            throw new IllegalStateException("No prompt template for category=" + category);
        }
        return new Rendered(
                yaml.getSystem(),
                PromptTemplateLoader.render(yaml.getUser(), params),
                yaml.getModel(),
                yaml.getTemperature(),
                yaml.getMaxTokens(),
                null,
                yaml.getRouting()
        );
    }

    /**
     * 渲染来自 DB 的 yaml 字符串(typically preset.{script,storyboard,critic}_prompt_yaml)。
     * yaml 必须包含 system / user 字段;model / temperature / max_tokens 可选,空时返回 null。
     */
    public Rendered renderInline(String yamlContent, Map<String, ?> params) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalStateException("renderInline: yamlContent 为空");
        }
        try {
            PromptTemplateLoader.PromptYaml yaml = INLINE_YAML.readValue(
                    yamlContent, PromptTemplateLoader.PromptYaml.class);
            return new Rendered(
                    yaml.getSystem(),
                    PromptTemplateLoader.render(yaml.getUser(), params),
                    yaml.getModel(),
                    yaml.getTemperature(),
                    yaml.getMaxTokens(),
                    null,
                    yaml.getRouting()
            );
        } catch (Exception e) {
            throw new IllegalStateException("renderInline: yaml 解析失败 — " + e.getMessage(), e);
        }
    }
}
