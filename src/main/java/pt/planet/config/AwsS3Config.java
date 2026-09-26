package pt.planet.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.aws.s3.enabled", havingValue = "true", matchIfMissing = true)
public class AwsS3Config {

    @Value("${app.aws.region:us-east-1}")
    private String region;

    @Value("${app.aws.access-key-id:test}")
    private String accessKeyId;

    @Value("${app.aws.secret-access-key:test}")
    private String secretAccessKey;

    @Value("${app.aws.s3.endpoint:}")
    private String endpoint;

    @Value("${app.aws.s3.public-endpoint:}")
    private String publicEndpoint;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder());

        if (endpoint != null && !endpoint.isBlank()) {
            log.info("Configuring S3Client with custom endpoint for LocalStack: {}", endpoint);
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region));

        String presignerEndpoint = (publicEndpoint != null && !publicEndpoint.isBlank())
                ? publicEndpoint
                : endpoint;

        if (presignerEndpoint != null && !presignerEndpoint.isBlank()) {
            log.info("Configuring S3Presigner with endpoint: {}", presignerEndpoint);
            builder.endpointOverride(URI.create(presignerEndpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }

        return builder.build();
    }
}
