package pt.planet.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationRecord(
        UUID notificationId,
        UUID exportId,
        String recipientEmail,
        String filename,
        String format,
        int recordCount,
        String presignedUrl,
        String status, // SENT, FAILED, SKIPPED
        String errorMessage,
        Instant processedAt
) {}
