package pt.planet.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import pt.planet.dto.ExportNotificationMessage;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMQNotificationProducer implements NotificationProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange:csv.export.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.notification-routing-key:notification.email}")
    private String routingKey;

    @Override
    public void sendExportNotification(ExportNotificationMessage message) {
        log.info("Publishing export notification to queue via exchange '{}' with routingKey '{}' for exportId: {}",
                exchange, routingKey, message.exportId());
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }
}
