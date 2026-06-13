package com.auteur.runtimeconfig;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第三方密钥/中转站等"用户每个部署都不一样"配置的运行时读取入口。
 *
 * 唯一来源:DB(app_config 表)。yml 不再参与 service 层读取。
 *
 * service 改密钥不重启即生效的:Voice / BGM / ExtensionTokenFilter(每请求查 cache)。
 * 改密钥需重启的:LLM RestClient / TOS Client(@PostConstruct 启动时一次 build)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfig {

    private final AppConfigRepository repo;
    private final Environment env;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            List<AppConfig> rows = repo.findAll();
            for (AppConfig c : rows) {
                if (c.getConfigValue() != null && !c.getConfigValue().isBlank()) {
                    cache.put(c.getConfigKey(), c.getConfigValue());
                }
            }
            log.info("[RuntimeConfig] 启动加载 {} 个 DB 配置项", cache.size());
            autoImportFromYml(rows);
        } catch (Exception e) {
            // 表不存在(冷启动 Flyway 之前)— 通常 init 不会被调用,但保险 swallow
            log.warn("[RuntimeConfig] 加载 app_config 失败: {}", e.toString());
        }
    }

    /**
     * 一次性迁移:遍历 DB 已有 key,若 value 为空但 application*.yml 里有值,把 yml 值灌进 DB。
     * 幂等 — 跑过一次后 DB value 不为空,后续启动不再 import。
     */
    @Transactional
    public void autoImportFromYml(List<AppConfig> rows) {
        int imported = 0;
        for (AppConfig row : rows) {
            String dbVal = row.getConfigValue();
            if (dbVal != null && !dbVal.isBlank()) continue;
            String fromYml = env.getProperty(row.getConfigKey());
            if (fromYml == null || fromYml.isBlank()) continue;
            row.setConfigValue(fromYml);
            repo.save(row);
            cache.put(row.getConfigKey(), fromYml);
            imported++;
        }
        if (imported > 0) {
            log.warn("[RuntimeConfig] 自动从 application*.yml 迁移 {} 个配置项到 DB —— "
                    + "请在「系统设置」UI 验证后,清理 application-local.yml 里的对应字段(以 DB 为准)。",
                    imported);
        }
    }

    /** 读配置 — 纯 DB,不 fallback yml。空 = 用户尚未配置。 */
    public String get(String key) {
        String v = cache.get(key);
        return v == null ? "" : v;
    }

    /** 读配置带技术型默认值(非密钥)。UI 没填则用调用方提供的兜底值。 */
    public String get(String key, String fallback) {
        String v = get(key);
        return v.isBlank() ? (fallback == null ? "" : fallback) : v;
    }

    public int getInt(String key, int fallback) {
        String v = get(key);
        if (v.isBlank()) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /** 同 getInt,但要求结果 > 0;非正数(0/负数/parse 失败/未配置)一律回 fallback。
     *  适用 fps / width / height / timeout / bitrate 等"语义上必须正"的字段。 */
    public int getIntPositive(String key, int fallback) {
        int v = getInt(key, fallback);
        return v > 0 ? v : fallback;
    }

    public double getDouble(String key, double fallback) {
        String v = get(key);
        if (v.isBlank()) return fallback;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /** 同 getDouble,但要求结果 > 0;非正数一律回 fallback。 */
    public double getDoublePositive(String key, double fallback) {
        double v = getDouble(key, fallback);
        return v > 0 ? v : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String v = get(key);
        if (v.isBlank()) return fallback;
        String t = v.trim().toLowerCase();
        // 接受 true/yes/on/1 — 大小写不敏感,空白已 trim
        if (t.equals("true") || t.equals("yes") || t.equals("on") || t.equals("1")) return true;
        if (t.equals("false") || t.equals("no") || t.equals("off") || t.equals("0")) return false;
        return fallback;
    }

    @Transactional
    public void set(String key, String value) {
        AppConfig row = repo.findById(key).orElseGet(() -> {
            AppConfig n = new AppConfig();
            n.setConfigKey(key);
            n.setCategory("custom");
            n.setSortOrder(999);
            return n;
        });
        String trimmed = value == null ? null : value.trim();
        row.setConfigValue(trimmed == null || trimmed.isEmpty() ? null : trimmed);
        repo.save(row);
        if (trimmed == null || trimmed.isEmpty()) {
            cache.remove(key);
        } else {
            cache.put(key, trimmed);
        }
    }

    @Transactional
    public void setAll(Map<String, String> kv) {
        kv.forEach(this::set);
    }

    /** 列出所有配置项;is_secret=true 时 displayValue 已 mask。 */
    public List<ConfigView> listAll() {
        List<AppConfig> rows = repo.findAllByOrderByCategoryAscSortOrderAsc();
        List<ConfigView> out = new java.util.ArrayList<>(rows.size());
        for (AppConfig c : rows) {
            String dbVal = c.getConfigValue();
            boolean hasDb = dbVal != null && !dbVal.isBlank();
            String effective = hasDb ? dbVal : "";
            out.add(new ConfigView(
                    c.getConfigKey(),
                    c.getDescription(),
                    c.isSecret(),
                    c.getCategory(),
                    c.getSortOrder(),
                    hasDb,
                    c.isSecret() ? maskSecret(effective) : effective,
                    c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString()));
        }
        return out;
    }

    private static String maskSecret(String v) {
        if (v == null || v.isBlank()) return "";
        if (v.length() <= 8) return "****";
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    public record ConfigView(
            String configKey,
            String description,
            boolean secret,
            String category,
            int sortOrder,
            boolean hasDbValue,
            String displayValue,
            String updatedAt
    ) {}
}
