package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sky.es")
public class EsProperties {
    private String uri;
    private String alias = "review";
    private int batchSize = 500;
    private int readTimeoutMs = 30000;
}
