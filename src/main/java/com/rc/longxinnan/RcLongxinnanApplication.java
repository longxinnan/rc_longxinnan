package com.rc.longxinnan;

import com.rc.longxinnan.config.PollerProperties;
import com.rc.longxinnan.config.ProviderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot 应用启动类。
 */
@SpringBootApplication
@EnableConfigurationProperties({ProviderProperties.class, PollerProperties.class})
public class RcLongxinnanApplication {

    public static void main(String[] args) {
        SpringApplication.run(RcLongxinnanApplication.class, args);
    }

}
