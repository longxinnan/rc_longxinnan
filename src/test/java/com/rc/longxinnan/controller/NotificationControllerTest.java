package com.rc.longxinnan.controller;

import com.rc.longxinnan.api.dto.NotificationResponse;
import com.rc.longxinnan.provider.UnknownProviderException;
import com.rc.longxinnan.service.NotificationIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationIngestService service;

    @Test
    void returns202OnValidRequest() throws Exception {
        when(service.submit(any())).thenReturn(new NotificationResponse(42L, "PENDING"));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType("application/json")
                        .content("""
                                {"eventId":"evt-1","provider":"crm","payload":{"a":1}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId").value(42))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void returns400OnMissingEventId() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType("application/json")
                        .content("""
                                {"provider":"crm","payload":{"a":1}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400OnUnknownProvider() throws Exception {
        when(service.submit(any())).thenThrow(new UnknownProviderException("nope"));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType("application/json")
                        .content("""
                                {"eventId":"evt-1","provider":"nope","payload":{"a":1}}
                                """))
                .andExpect(status().isBadRequest());
    }
}
