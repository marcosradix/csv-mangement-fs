package pt.planet.notification.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.planet.notification.dto.ExportNotificationMessage;
import pt.planet.notification.dto.NotificationRecord;
import pt.planet.notification.service.MailService;
import pt.planet.notification.service.NotificationHistoryService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationHistoryService historyService;
    private final MailService mailService;

    @GetMapping
    public ResponseEntity<List<NotificationRecord>> getRecentNotifications() {
        return ResponseEntity.ok(historyService.getRecentNotifications());
    }

    @GetMapping("/{exportId}")
    public ResponseEntity<NotificationRecord> getNotificationByExportId(@PathVariable UUID exportId) {
        return historyService.findByExportId(exportId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/send-test")
    public ResponseEntity<Map<String, Object>> sendTestEmail(
            @RequestParam(required = false, defaultValue = "test@example.com") String email,
            @RequestBody(required = false) Map<String, String> payload
    ) {
        String targetEmail = (payload != null && payload.containsKey("email"))
                ? payload.get("email")
                : email;

        UUID testExportId = UUID.randomUUID();
        String testFilename = "customers_test_" + System.currentTimeMillis() + ".csv";
        String testPresignedUrl = "http://localhost:4566/customer-exports/exports/" + testFilename + "?test=true";

        ExportNotificationMessage testMessage = new ExportNotificationMessage(
                testExportId,
                targetEmail,
                testFilename,
                "CSV",
                "customer-exports",
                "exports/" + testFilename,
                testPresignedUrl,
                10,
                Instant.now()
        );

        try {
            mailService.sendExportNotificationEmail(testMessage);
            historyService.recordNotification(new NotificationRecord(
                    UUID.randomUUID(),
                    testExportId,
                    targetEmail,
                    testFilename,
                    "CSV",
                    10,
                    testPresignedUrl,
                    "SENT",
                    null,
                    Instant.now()
            ));
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Test notification email sent to " + targetEmail,
                    "exportId", testExportId,
                    "presignedUrl", testPresignedUrl
            ));
        } catch (Exception e) {
            log.error("Failed to send test email to {}: {}", targetEmail, e.getMessage(), e);
            historyService.recordNotification(new NotificationRecord(
                    UUID.randomUUID(),
                    testExportId,
                    targetEmail,
                    testFilename,
                    "CSV",
                    10,
                    testPresignedUrl,
                    "FAILED",
                    e.getMessage(),
                    Instant.now()
            ));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "ERROR",
                    "message", "Failed to send email: " + e.getMessage(),
                    "exportId", testExportId
            ));
        }
    }
}
