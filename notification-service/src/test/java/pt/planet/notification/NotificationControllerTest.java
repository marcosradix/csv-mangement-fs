package pt.planet.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import pt.planet.notification.controller.NotificationController;
import pt.planet.notification.dto.NotificationRecord;
import pt.planet.notification.service.MailService;
import pt.planet.notification.service.NotificationHistoryService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationHistoryService historyService;

    @MockBean
    private MailService mailService;

    @Test
    @DisplayName("GET /api/v1/notifications should return list of notifications")
    void testGetRecentNotifications() throws Exception {
        UUID exportId = UUID.randomUUID();
        NotificationRecord record = new NotificationRecord(
                UUID.randomUUID(),
                exportId,
                "user@example.com",
                "export.csv",
                "CSV",
                5,
                "http://localhost:4566/url",
                "SENT",
                null,
                Instant.now()
        );

        when(historyService.getRecentNotifications()).thenReturn(List.of(record));

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exportId").value(exportId.toString()))
                .andExpect(jsonPath("$[0].recipientEmail").value("user@example.com"))
                .andExpect(jsonPath("$[0].status").value("SENT"));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{exportId} should return 404 when not found")
    void testGetNotificationByExportId_NotFound() throws Exception {
        UUID exportId = UUID.randomUUID();
        when(historyService.findByExportId(exportId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/notifications/{exportId}", exportId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/notifications/send-test should send test email and return 200")
    void testSendTestEmail() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/send-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"temp@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Test notification email sent to temp@example.com"));
    }
}
