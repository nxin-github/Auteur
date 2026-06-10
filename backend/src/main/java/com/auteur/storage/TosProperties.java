package com.auteur.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "auteur.tos")
public class TosProperties {
    private String endpoint;
    private String region;
    private String accessKey;
    private String secretKey;
    private String bucket = "nx-zimeiti";
}
