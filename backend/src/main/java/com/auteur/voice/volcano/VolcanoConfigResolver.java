package com.auteur.voice.volcano;

import com.auteur.runtimeconfig.RuntimeConfig;
import com.auteur.voice.VoiceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把 VoiceProperties + RuntimeConfig 合并成"本次请求用的有效 Volcano 配置"。
 *
 * 单一来源,避免 VolcanoVoiceClient 和 VoiceDemoService 各自手抄一份字段拷贝
 * (V10 加 asyncQueryTimeoutSeconds / asyncDownloadTimeoutSeconds 两个新字段时,
 * VolcanoVoiceClient.effectiveVolcano 加了,VoiceDemoService 漏了 — 就是这个重复的代价)。
 *
 * 数值类字段一律走 getIntPositive:DB 误填 0/负数时回落 yml 默认。
 */
@Component
@RequiredArgsConstructor
public class VolcanoConfigResolver {

    private final RuntimeConfig runtimeConfig;

    /** 完整版:含 secret(api-key 等),用于实际 TTS 请求。 */
    public VoiceProperties.Volcano resolve(VoiceProperties props) {
        VoiceProperties.Volcano base = props.getVolcano();
        VoiceProperties.Volcano c = new VoiceProperties.Volcano();
        c.setBaseUrl(runtimeConfig.get("auteur.voice.volcano.base-url", base.getBaseUrl()));
        c.setHttpTimeoutSeconds(runtimeConfig.getIntPositive("auteur.voice.volcano.http-timeout-seconds", base.getHttpTimeoutSeconds()));
        c.setDemoText(runtimeConfig.get("auteur.voice.volcano.demo-text", base.getDemoText()));
        c.setAsyncMode(runtimeConfig.getBoolean("auteur.voice.volcano.async-mode", base.isAsyncMode()));
        c.setAsyncPollIntervalSec(runtimeConfig.getIntPositive("auteur.voice.volcano.async-poll-interval-sec", base.getAsyncPollIntervalSec()));
        c.setAsyncMaxWaitSec(runtimeConfig.getIntPositive("auteur.voice.volcano.async-max-wait-sec", base.getAsyncMaxWaitSec()));
        c.setAsyncQueryTimeoutSeconds(runtimeConfig.getIntPositive("auteur.voice.volcano.async-query-timeout-seconds", base.getAsyncQueryTimeoutSeconds()));
        c.setAsyncDownloadTimeoutSeconds(runtimeConfig.getIntPositive("auteur.voice.volcano.async-download-timeout-seconds", base.getAsyncDownloadTimeoutSeconds()));
        c.setApiKey(runtimeConfig.get("auteur.voice.volcano.api-key"));
        c.setAppKey(runtimeConfig.get("auteur.voice.volcano.app-key"));
        c.setAccessKey(runtimeConfig.get("auteur.voice.volcano.access-key"));
        c.setResourceId(runtimeConfig.get("auteur.voice.volcano.resource-id"));
        return c;
    }
}
