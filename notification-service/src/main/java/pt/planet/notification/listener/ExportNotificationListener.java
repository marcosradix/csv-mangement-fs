package pt.planet.notification.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import pt.planet.notification.dto.ExportNotificationMessage;
import pt.planet.notification.dto.NotificationRecord;
import pt.planet.notification.service.MailService;
import pt.planet.notification.service.NotificationHistoryService;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExportNotificationListener {

    private final MailService mailService;
    private final NotificationHistoryService historyService;

    @RabbitListener(
            queues = "${app.rabbitmq.notification-queue:notification-service}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void onExportNotification(ExportNotificationMessage message) {
        log.info("Received export notification event from queue: exportId={}, filename={}, email={}",
                message.exportId(), message.filename(), message.email());

        UUID notificationId = UUID.randomUUID();
        String recipient = message.email();

        if (recipient == null || recipient.isBlank()) {
            log.warn("Export {} did not include a recipient email. Skipping email delivery.", message.exportId());
            historyService.recordNotification(new NotificationRecord(
                    notificationId,
                    message.exportId(),
                    recipient,
                    message.filename(),
                    message.format(),
                    message.recordCount(),
                    message.presignedUrl(),
                    "SKIPPED",
                    "No recipient email specified in request header",
                    Instant.now()
            ));
            return;
        }

        try {
            mailService.sendExportNotificationEmail(message);
            historyService.recordNotification(new NotificationRecord(
                    notificationId,
                    message.exportId(),
                    recipient,
                    message.filename(),
                    message.format(),
                    message.recordCount(),
                    message.presignedUrl(),
                    "SENT",
                    null,
                    Instant.now()
            ));
            log.info("Processed export notification {} successfully for recipient {}", notificationId, recipient);
        } catch (Exception e) {
            log.error("Failed to deliver export email to '{}' for export {}: {}",
                    recipient, message.exportId(), e.getMessage(), e);

            historyService.recordNotification(new NotificationRecord(
                    notificationId,
                    message.exportId(),
                    recipient,
                    message.filename(),
                    message.format(),
                    message.recordCount(),
                    message.presignedUrl(),
                    "FAILED",
                    e.getMessage(),
                    Instant.now()
            ));
        }
    }
}
