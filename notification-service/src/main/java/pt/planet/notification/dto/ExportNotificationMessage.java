package pt.planet.notification.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record ExportNotificationMessage(
        UUID exportId,
        String email,
        String filename,
        String format,
        String s3Bucket,
        String s3Key,
        String presignedUrl,
        int recordCount,
        Instant createdAt
) implements Serializable {}
