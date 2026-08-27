package com.rc.longxinnan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 轮询器配置：app.poller.*。
 */
@ConfigurationProperties(prefix = "app.poller")
public record PollerProperties(long fixedDelayMs, int batchSize, int poolSize) {

    public PollerProperties {
        if (fixedDelayMs <= 0) {
            fixedDelayMs = 2000;
        }
        if (batchSize <= 0) {
            batchSize = 50;
        }
        if (poolSize <= 0) {
            poolSize = 1;
        }
    }
}
