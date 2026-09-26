package pt.planet.notification;

import pt.planet.dto.ExportNotificationMessage;

public interface NotificationProducer {

    /**
     * Publishes export completion event to the notification-service queue.
     *
     * @param message payload containing export metadata and pre-signed download URL
     */
    void sendExportNotification(ExportNotificationMessage message);
}
