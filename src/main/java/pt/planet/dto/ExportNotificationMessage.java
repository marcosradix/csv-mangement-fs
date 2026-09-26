package pt.planet.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Message payload published to the 'notification-service' RabbitMQ queue
 * after an export file has been processed and uploaded to AWS S3.
 */
public record ExportNotificationMessage(
        UUID exportId,
        String email,
        String filename,
        String format,
        String s3Bucket,
        String s3Key,
        String presignedUrl,
        long recordCount,
        Instant createdAt
) implements Serializable {
}
