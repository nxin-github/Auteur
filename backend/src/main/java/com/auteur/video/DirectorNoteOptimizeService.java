package com.auteur.video;

import com.auteur.common.text.TextUtils;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 总导演笔记 "AI 智能填充" 服务。把"用户当前填的内容(可能含未保存草稿)+ 自然语言诉求"喂给 LLM,
 * 重写整份 DirectorNote(tone/pacing/五段 narrativeArc/visualStyle/protagonistVibe/keyMoments/
 * highlightThemes/directorNotes),返回完整对象 + explanation。
 *
 * 不落库 — 仅返回建议,前端写回 form,用户决定是否点保存。
 *
 * 与 {@link DirectorNoteService}(剧组群聊增量,append-only)无任何关系,仅同名容易混淆,这里明确分离。
 *
 * 镜像 {@link com.auteur.preset.PresetOptimizeService}:system prompt 写死 schema 约束 + 严格 JSON 输出,
 * 解析阶段 stripCodeFence → 截最外层 {} → readValue → 硬/软校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorNoteOptimizeService {

    private static final List<String> REQUIRED_ARC_SECTIONS = List.of("A", "B", "C", "D", "E");
    private static final int HIGHLIGHT_THEMES_CAP = 8;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final TopicRepository topicRepository;

    public OptimizeResponse optimize(Long topicId, OptimizeRequest req) {
        if (topicId == null || topicId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topicId 非法");
        }
        // userFeedback 允许为空(初次生成或用户没有具体诉求,让模型按 Topic 上下文自行判断)
        String feedback = (req == null || req.userFeedback() == null) ? "" : req.userFeedback().trim();
        Topic topic = topicRepository.findById(topicId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "topic " + topicId + " 不存在"));

        // currentValues 优先用前端传的(含未保存草稿),为空则回落 DB 里的 directorNote。
        JsonNode currentNote = req.currentValues();
        if (currentNote == null || currentNote.isNull()) {
            currentNote = parseJsonOrEmpty(topic.getDirectorNote());
        }

        String currentJson = toPrettyJson(currentNote);
        String topicCtx = buildTopicContext(topic);

        String system = """
                你是 Auteur 视频创作工具的"总导演笔记重写助手"。
                用户给出当前导演笔记草稿(可能不完整或为空)+ 自然语言诉求,你要综合两者重写整份导演笔记。

                【输出要求】
                1. 严格 JSON,不要 Markdown 代码块,不要前后任何解释文字。
                2. JSON 顶层只有两个 key:note 与 explanation。
                3. note 的 shape 必须严格符合下列 schema(任何缺失或类型不符都不接受):
                   {
                     "tone": "<非空字符串,整体调性>",
                     "pacing": "<非空字符串,整体节奏>",
                     "narrativeArc": [
                       { "section": "A", "guidance": "<非空字符串>" },
                       { "section": "B", "guidance": "<非空字符串>" },
                       { "section": "C", "guidance": "<非空字符串>" },
                       { "section": "D", "guidance": "<非空字符串>" },
                       { "section": "E", "guidance": "<非空字符串>" }
                     ],
                     "visualStyle": {
                       "palette": "<字符串>",
                       "depthOfField": "<字符串>",
                       "lighting": "<字符串>",
                       "avoidWords": ["<字符串>", ...]
                     },
                     "protagonistVibe": {
                       "appearance": "<字符串>",
                       "voiceVibe": "<字符串>",
                       "speakingPace": "<字符串>"
                     },
                     "keyMoments": [{ "time": "<非空>", "what": "<非空>" }, ...],
                     "highlightThemes": ["<字符串>", ...],
                     "directorNotes": "<散文体补充指令,可空字符串。绝对不要 Markdown 或代码块>"
                   }
                4. narrativeArc 严格 5 段,顺序固定 A→B→C→D→E,不许多不许少不许乱序。
                5. keyMoments 推荐 2-4 个;highlightThemes 推荐 3-8 个。
                6. 如果用户当前已填了某些内容,在合理范围内尽量保留其语感,不要无故抹平用户已经写好的措辞。
                7. explanation 是一句中文,30-120 字,概括你做了哪些调整。
                8. **JSON 结构纪律(违反就解析失败)**:
                   - 每个 `{` 必须对应一个 `}` 关闭后才进逗号或下一个键
                   - 数组里每个对象先写 `}` 关闭对象,再写 `,` 连接下一个,最后用 `]` 关闭数组
                   - narrativeArc 5 个 element 都是对象 — 每个对象都用 `}` 闭合,数组整体用 `]` 闭合,
                     不要把 `]` 当成对象闭合写在 element 内部
                """;

        String userFeedbackBlock = feedback.isEmpty()
                ? "(用户未给出具体诉求 — 请你结合 Topic 上下文与当前草稿,产出最贴合本片题材/情绪的整份导演笔记。"
                  + "若当前草稿是空对象,从零生成;若已有部分内容,尽量保留其语感的同时把缺的字段补齐、不合理的地方调顺。)"
                : feedback;

        String user = """
                你正在重写一个 Topic 的导演笔记。

                ====== Topic 上下文 ======
                %s

                ====== 当前导演笔记草稿(JSON,可能为空对象表示完全空白)======
                %s

                ====== 用户诉求 ======
                %s

                请综合上述信息重写整份 DirectorNote。直接输出符合上述【输出要求】的 JSON。
                """.formatted(topicCtx, currentJson, userFeedbackBlock);

        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("director_note_optimize")
                .relatedType("TOPIC")
                .relatedId(topicId)
                .temperature(0.5)
                // 整份 DirectorNote JSON 含 5 段 narrativeArc + visualStyle + protagonistVibe +
                // keyMoments + highlightThemes + directorNotes,实测中文输出 1500-3000 字符 /
                // 1000-1500 tokens。不设 maxTokens 时 DeepSeek 默认 1024 会截断 → JSON 解析失败。
                .maxTokens(4000)
                .build();

        // LLM 偶发结构性错误(数组对象嵌套漏 } 之类),不是 healer 能修的字符级问题 —
        // 整次重新生成成功率 > 修复尝试。最多重试 2 次,加起来约 60-80s。
        ResponseStatusException lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            LlmResult result = llmClient.chat(spec, system, user);
            String raw = result.getContent();
            log.info("[DirectorNoteOptimize] topicId={} attempt={} feedbackChars={} outChars={} ms={}",
                    topicId, attempt, feedback.length(),
                    raw == null ? 0 : raw.length(), result.getDurationMs());
            try {
                return parseAndValidate(raw);
            } catch (ResponseStatusException e) {
                lastErr = e;
                if (attempt < 2) {
                    log.warn("[DirectorNoteOptimize] attempt={} parse failed, retrying once: {}",
                            attempt, e.getReason());
                }
            }
        }
        throw lastErr;
    }

    private String buildTopicContext(Topic topic) {
        StringBuilder sb = new StringBuilder();
        sb.append("title: ").append(TextUtils.safe(topic.getTitle())).append('\n');
        if (topic.getDynasty() != null && !topic.getDynasty().isBlank()) {
            sb.append("dynasty: ").append(topic.getDynasty()).append('\n');
        }
        if (topic.getGenre() != null && !topic.getGenre().isBlank()) {
            sb.append("genre: ").append(topic.getGenre()).append('\n');
        }
        if (topic.getProtagonist() != null && !topic.getProtagonist().isBlank()) {
            sb.append("protagonist: ").append(topic.getProtagonist()).append('\n');
        }
        if (topic.getEmotion() != null && !topic.getEmotion().isBlank()) {
            sb.append("emotion: ").append(topic.getEmotion()).append('\n');
        }
        if (topic.getNotes() != null && !topic.getNotes().isBlank()) {
            sb.append("notes: ").append(topic.getNotes()).append('\n');
        }
        return sb.toString();
    }

    private OptimizeResponse parseAndValidate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LLM 返回为空");
        }
        String stripped = TextUtils.stripCodeFence(raw).trim();
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LLM 输出不是合法 JSON: " + TextUtils.preview(raw));
        }
        String json = stripped.substring(start, end + 1);
        Map<String, Object> parsed;
        try {
            // LLM 经常在中文 prompt 内嵌套双引号(如「" "」),JsonHealer 修常见 unescaped 双引号
            String healed = com.auteur.llm.JsonHealer.fixUnescapedAsciiQuotes(json);
            parsed = objectMapper.readValue(healed, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 完整 raw 落 log 便于复盘 — preview 只截 200 字看不见根因
            log.warn("[DirectorNoteOptimize] parse failed: {}\nFULL raw=\n{}", e.toString(), raw);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "LLM 输出 JSON 解析失败: " + TextUtils.preview(raw));
        }

        Object noteObj = parsed.get("note");
        if (!(noteObj instanceof Map<?, ?> noteMap)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LLM 输出缺少 note 对象");
        }
        JsonNode noteNode = objectMapper.valueToTree(noteMap);
        if (!(noteNode instanceof ObjectNode noteOn)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LLM 输出 note 不是对象");
        }

        validateAndPatch(noteOn);

        String explanation = parsed.get("explanation") == null
                ? null : String.valueOf(parsed.get("explanation"));
        return new OptimizeResponse(noteOn, explanation);
    }

    /**
     * 硬校验失败抛 400;软校验只 log warn 后就地修复 ObjectNode。
     */
    private void validateAndPatch(ObjectNode note) {
        // 硬:tone/pacing 非空字符串
        requireNonBlankString(note, "tone");
        requireNonBlankString(note, "pacing");

        // 硬:narrativeArc 严格 5 段,顺序 [A,B,C,D,E],guidance 非空
        JsonNode arcNode = note.get("narrativeArc");
        if (arcNode == null || !arcNode.isArray() || arcNode.size() != 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "narrativeArc 必须是长度为 5 的数组");
        }
        for (int i = 0; i < 5; i++) {
            JsonNode item = arcNode.get(i);
            String expected = REQUIRED_ARC_SECTIONS.get(i);
            if (item == null || !item.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "narrativeArc[" + i + "] 不是对象");
            }
            String section = item.path("section").asText("");
            String guidance = item.path("guidance").asText("");
            if (!expected.equals(section)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "narrativeArc[" + i + "].section 必须是 \"" + expected + "\",得到 \"" + section + "\"");
            }
            if (guidance.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "narrativeArc[" + i + "].guidance 不能为空");
            }
        }

        // 硬:visualStyle / protagonistVibe 是对象
        JsonNode visualStyle = note.get("visualStyle");
        if (visualStyle == null || !visualStyle.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visualStyle 必须是对象");
        }
        JsonNode protagonistVibe = note.get("protagonistVibe");
        if (protagonistVibe == null || !protagonistVibe.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "protagonistVibe 必须是对象");
        }

        // 软:visualStyle 子字段缺失补 ""、avoidWords 过滤空白
        ObjectNode vsOn = (ObjectNode) visualStyle;
        defaultBlankString(vsOn, "palette");
        defaultBlankString(vsOn, "depthOfField");
        defaultBlankString(vsOn, "lighting");
        JsonNode avoidWords = vsOn.get("avoidWords");
        if (avoidWords == null || !avoidWords.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visualStyle.avoidWords 必须是数组");
        }
        vsOn.set("avoidWords", filterBlankStrings((ArrayNode) avoidWords));

        // 软:protagonistVibe 子字段缺失补 ""
        ObjectNode pvOn = (ObjectNode) protagonistVibe;
        defaultBlankString(pvOn, "appearance");
        defaultBlankString(pvOn, "voiceVibe");
        defaultBlankString(pvOn, "speakingPace");

        // 硬:keyMoments / highlightThemes 是数组
        JsonNode keyMoments = note.get("keyMoments");
        if (keyMoments == null || !keyMoments.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyMoments 必须是数组");
        }
        // 软:keyMoments 内 time 或 what 空 → 丢
        ArrayNode kmFiltered = objectMapper.createArrayNode();
        int kmDropped = 0;
        for (JsonNode m : keyMoments) {
            String time = m.path("time").asText("").trim();
            String what = m.path("what").asText("").trim();
            if (time.isBlank() || what.isBlank()) { kmDropped++; continue; }
            ObjectNode km = objectMapper.createObjectNode();
            km.put("time", time);
            km.put("what", what);
            kmFiltered.add(km);
        }
        if (kmDropped > 0) {
            log.warn("[DirectorNoteOptimize] keyMoments 丢弃 {} 个空项", kmDropped);
        }
        note.set("keyMoments", kmFiltered);

        JsonNode highlightThemes = note.get("highlightThemes");
        if (highlightThemes == null || !highlightThemes.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "highlightThemes 必须是数组");
        }
        // 软:超 8 截前 8、过滤空白
        ArrayNode htFiltered = filterBlankStrings((ArrayNode) highlightThemes);
        if (htFiltered.size() > HIGHLIGHT_THEMES_CAP) {
            log.warn("[DirectorNoteOptimize] highlightThemes 数量 {} 超 {},截前 {}",
                    htFiltered.size(), HIGHLIGHT_THEMES_CAP, HIGHLIGHT_THEMES_CAP);
            ArrayNode capped = objectMapper.createArrayNode();
            for (int i = 0; i < HIGHLIGHT_THEMES_CAP; i++) capped.add(htFiltered.get(i));
            htFiltered = capped;
        }
        note.set("highlightThemes", htFiltered);

        // 软:directorNotes 缺失补 ""
        if (!note.hasNonNull("directorNotes")) {
            note.put("directorNotes", "");
        }
    }

    private void requireNonBlankString(ObjectNode obj, String field) {
        JsonNode v = obj.get(field);
        if (v == null || !v.isTextual() || v.asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须是非空字符串");
        }
    }

    private void defaultBlankString(ObjectNode obj, String field) {
        JsonNode v = obj.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            obj.put(field, "");
        }
    }

    private ArrayNode filterBlankStrings(ArrayNode arr) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode n : arr) {
            if (n == null || !n.isTextual()) continue;
            String s = n.asText("").trim();
            if (s.isBlank()) continue;
            out.add(s);
        }
        return out;
    }

    private JsonNode parseJsonOrEmpty(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("[DirectorNoteOptimize] DB directorNote JSON 解析失败,回落空对象: {}", e.toString());
            return objectMapper.createObjectNode();
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public record OptimizeRequest(String userFeedback, JsonNode currentValues) {}

    public record OptimizeResponse(JsonNode note, String explanation) {}
}
