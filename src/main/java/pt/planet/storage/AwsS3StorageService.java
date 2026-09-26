package pt.planet.storage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.aws.s3.enabled", havingValue = "true", matchIfMissing = true)
public class AwsS3StorageService implements S3StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${app.aws.s3.bucket:customer-exports}")
    private String bucket;

    @Value("${app.aws.s3.presigned-url-expiration-minutes:1440}")
    private long presignedExpirationMinutes;

    @PostConstruct
    public void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            log.info("Bucket '{}' does not exist yet. Creating bucket...", bucket);
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Bucket '{}' created successfully.", bucket);
            } catch (Exception ex) {
                log.warn("Could not create bucket '{}': {}", bucket, ex.getMessage());
            }
        }
    }

    @Override
    public String upload(String key, byte[] content, String contentType) {
        log.info("Uploading file '{}' to S3 bucket '{}' (size: {} bytes, contentType: '{}')",
                key, bucket, content.length, contentType);
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        s3Client.putObject(putRequest, RequestBody.fromBytes(content));
        return key;
    }

    @Override
    public String generatePresignedUrl(String key, Duration duration) {
        Duration actualDuration = duration != null ? duration : Duration.ofMinutes(presignedExpirationMinutes);
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(actualDuration)
                .getObjectRequest(b -> b.bucket(bucket).key(key))
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    @Override
    public String getBucket() {
        return bucket;
    }
}
