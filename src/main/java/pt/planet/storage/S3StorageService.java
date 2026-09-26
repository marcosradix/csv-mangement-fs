package pt.planet.storage;

import java.time.Duration;

public interface S3StorageService {

    /**
     * Uploads content to S3 storage.
     *
     * @param key         S3 object key
     * @param content     file content bytes
     * @param contentType MIME type of the file
     * @return the S3 object key
     */
    String upload(String key, byte[] content, String contentType);

    /**
     * Generates a pre-signed download URL for the uploaded S3 object.
     *
     * @param key      S3 object key
     * @param duration URL validity duration (or null to use default)
     * @return pre-signed download URL
     */
    String generatePresignedUrl(String key, Duration duration);

    /**
     * Gets the configured S3 bucket name.
     *
     * @return bucket name
     */
    String getBucket();
}
