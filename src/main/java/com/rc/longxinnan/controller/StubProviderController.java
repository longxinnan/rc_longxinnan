package com.rc.longxinnan.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地联调用的假供应商端点：echo 收到的请求。
 *
 * <p>仅当启用 {@code stub} profile 时注册（{@code ./mvnw spring-boot:run -Dspring-boot.run.profiles=stub}），
 * 把供应商 yml 的 url 指到 {@code http://localhost:8080/stub/echo} 即可观察投递请求。
 */
@RestController
@Profile("stub")
public class StubProviderController {

    private static final Logger log = LoggerFactory.getLogger(StubProviderController.class);

    @PostMapping("/stub/echo")
    public ResponseEntity<Map<String, Object>> echo(@RequestBody(required = false) Map<String, Object> body,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        log.info("STUB received X-Request-Id={} body={}", requestId, body);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
