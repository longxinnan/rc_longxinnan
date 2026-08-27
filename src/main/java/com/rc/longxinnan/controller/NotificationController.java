package com.rc.longxinnan.controller;

import com.rc.longxinnan.api.dto.NotificationRequest;
import com.rc.longxinnan.api.dto.NotificationResponse;
import com.rc.longxinnan.provider.UnknownProviderException;
import com.rc.longxinnan.service.NotificationIngestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通知入站接口。契约统一 202：无论内部同步投递成败，对业务系统都不返回供应商结果。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationIngestService service;

    public NotificationController(NotificationIngestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<NotificationResponse> submit(@Valid @RequestBody NotificationRequest request) {
        NotificationResponse response = service.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @ExceptionHandler(UnknownProviderException.class)
    public ResponseEntity<Map<String, String>> handleUnknownProvider(UnknownProviderException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
