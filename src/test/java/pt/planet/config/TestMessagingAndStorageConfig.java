package pt.planet.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import pt.planet.dto.ExportNotificationMessage;
import pt.planet.notification.NotificationProducer;
import pt.planet.storage.S3StorageService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@TestConfiguration
public class TestMessagingAndStorageConfig {

    public static class InMemoryS3StorageService implements S3StorageService {
        private final Map<String, byte[]> storage = new ConcurrentHashMap<>();

        @Override
        public String upload(String key, byte[] content, String contentType) {
            storage.put(key, content);
            return key;
        }

        @Override
        public String generatePresignedUrl(String key, Duration duration) {
            return "https://test-bucket.s3.amazonaws.com/" + key + "?signed=true";
        }

        @Override
        public String getBucket() {
            return "test-bucket";
        }

        public Map<String, byte[]> getStorage() {
            return storage;
        }
    }

    public static class InMemoryNotificationProducer implements NotificationProducer {
        private final List<ExportNotificationMessage> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void sendExportNotification(ExportNotificationMessage message) {
            messages.add(message);
        }

        public List<ExportNotificationMessage> getMessages() {
            return messages;
        }
    }

    @Bean
    @Primary
    public S3StorageService testS3StorageService() {
        return new InMemoryS3StorageService();
    }

    @Bean
    @Primary
    public NotificationProducer testNotificationProducer() {
        return new InMemoryNotificationProducer();
    }
}
