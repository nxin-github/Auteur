package com.auteur.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.auth.StaticCredentials;
import com.volcengine.tos.model.object.PutObjectInput;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 火山引擎 TOS 对象存储上传服务。
 *
 * 公网 URL 格式：https://{bucket}.{endpoint}/{key}
 * 对象 key 约定：scripts/{scriptId}/{type}/{filename}
 *   type = images | voice | video | cover
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(TosProperties.class)
public class TosStorageService {

    private final TosProperties props;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;
    private TOSV2 tos;
    /** 启动时锁定一次的有效配置;UI 改值后需重启后端才会重 build。 */
    private String effectiveBucket;
    private String effectiveEndpoint;

    @PostConstruct
    public void init() {
        // 非 secret 字段(endpoint/region/bucket)允许 yml 默认;secret 字段必须从 DB(UI 配置)
        String region    = runtimeConfig.get("auteur.tos.region",      props.getRegion());
        String endpoint  = runtimeConfig.get("auteur.tos.endpoint",    props.getEndpoint());
        String bucket    = runtimeConfig.get("auteur.tos.bucket",      props.getBucket());
        String accessKey = runtimeConfig.get("auteur.tos.access-key");
        String secretKey = runtimeConfig.get("auteur.tos.secret-key");
        if (region.isBlank() || endpoint.isBlank() || accessKey.isBlank() || secretKey.isBlank() || bucket.isBlank()) {
            log.warn("[TOS] 配置不完整(region/endpoint/access-key/secret-key/bucket 至少一个空),上传功能不可用。请到「系统设置」配置。");
            return;
        }
        tos = new TOSV2ClientBuilder().build(region, endpoint, new StaticCredentials(accessKey, secretKey));
        this.effectiveBucket = bucket;
        this.effectiveEndpoint = endpoint;
        log.info("[TOS] 初始化完成 bucket={} endpoint={}", bucket, endpoint);
    }

    /** 上传字节数组，返回公网 URL。 */
    public String upload(String key, byte[] data, String contentType) {
        ensureReady();
        PutObjectInput input = new PutObjectInput()
                .setBucket(effectiveBucket)
                .setKey(key)
                .setContent(new ByteArrayInputStream(data))
                .setContentLength((long) data.length);
        tos.putObject(input);
        String url = toPublicUrl(key);
        log.info("[TOS] uploaded key={} size={} url={}", key, data.length, url);
        return url;
    }

    /** 上传本地文件，返回公网 URL。上传后可自行决定是否删除本地文件。 */
    public String upload(String key, Path localFile, String contentType) {
        try {
            byte[] data = Files.readAllBytes(localFile);
            return upload(key, data, contentType);
        } catch (IOException e) {
            throw new RuntimeException("TOS upload failed for key=" + key + ": " + e.getMessage(), e);
        }
    }

    /** 上传 InputStream，size 必须已知。 */
    public String upload(String key, InputStream stream, long size, String contentType) {
        ensureReady();
        PutObjectInput input = new PutObjectInput()
                .setBucket(effectiveBucket)
                .setKey(key)
                .setContent(stream)
                .setContentLength(size);
        tos.putObject(input);
        String url = toPublicUrl(key);
        log.info("[TOS] uploaded key={} size={} url={}", key, size, url);
        return url;
    }

    /** 构造 key：scripts/{scriptId}/{type}/{filename} */
    public static String buildKey(Long scriptId, String type, String filename) {
        return String.format("scripts/%d/%s/%s", scriptId, type, filename);
    }

    private void ensureReady() {
        if (tos == null) {
            throw new IllegalStateException("TOS 未配置 — 请到「系统设置 → 对象存储」填写 access-key/secret-key/bucket 并重启后端");
        }
    }

    private String toPublicUrl(String key) {
        return String.format("https://%s.%s/%s", effectiveBucket, effectiveEndpoint, key);
    }
}
