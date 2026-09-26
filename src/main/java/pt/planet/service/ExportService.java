package pt.planet.service;

import io.micrometer.observation.annotation.Observed;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.planet.domain.CustomerEntity;
import pt.planet.dto.ExportMetadata;
import pt.planet.dto.ExportRequest;
import pt.planet.dto.ExportRequest.FormatEnum;
import pt.planet.dto.ExportResult;
import pt.planet.exception.InvalidColumnException;
import pt.planet.exception.InvalidExportFormatException;
import pt.planet.exception.InvalidFileException;
import pt.planet.exportfile.CustomerBatchSupplier;
import pt.planet.exportfile.CustomerColumn;
import pt.planet.exportfile.ExportStrategy;
import pt.planet.observability.AppMetricsService;
import pt.planet.repository.CustomerRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import pt.planet.notification.NotificationProducer;
import pt.planet.storage.S3StorageService;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service orchestrating customer data export operations across multiple formats (CSV, TXT, XLSX).
 * Implements the Keyset (Cursor-Based) Pagination pattern to retrieve database records in bounded
 * batches, maintaining a constant O(1) memory footprint and avoiding OutOfMemoryError on large tables.
 */
@Slf4j
@Service
public class ExportService {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    private final List<ExportStrategy> exportStrategies;
    private final CustomerRepository customerRepository;
    private final AppMetricsService appMetricsService;
    private final EntityManager entityManager;
    private final int batchSize;
    private final S3StorageService s3StorageService;
    private final NotificationProducer notificationProducer;
    private final Executor executor;

    @Autowired
    public ExportService(
            List<ExportStrategy> exportStrategies,
            CustomerRepository customerRepository,
            AppMetricsService appMetricsService,
            EntityManager entityManager,
            @Value("${app.export.batch-size}") int batchSize,
            @Autowired(required = false) S3StorageService s3StorageService,
            @Autowired(required = false) NotificationProducer notificationProducer,
            @Autowired(required = false) @Qualifier("exportTaskExecutor") Executor executor
    ) {
        this.exportStrategies = exportStrategies;
        this.customerRepository = customerRepository;
        this.appMetricsService = appMetricsService;
        this.entityManager = entityManager;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        this.s3StorageService = s3StorageService;
        this.notificationProducer = notificationProducer;
        this.executor = executor;
    }

    public ExportService(
            List<ExportStrategy> exportStrategies,
            CustomerRepository customerRepository,
            AppMetricsService appMetricsService,
            EntityManager entityManager,
            int batchSize
    ) {
        this(exportStrategies, customerRepository, appMetricsService, entityManager, batchSize, null, null, null);
    }

    public ExportService(
            List<ExportStrategy> exportStrategies,
            CustomerRepository customerRepository,
            AppMetricsService appMetricsService,
            EntityManager entityManager
    ) {
        this(exportStrategies, customerRepository, appMetricsService, entityManager, DEFAULT_BATCH_SIZE, null, null, null);
    }

    public pt.planet.dto.ExportAsyncResponse processExportAsync(ExportRequest request, String userEmail) {
        if (request == null) {
            throw new InvalidFileException("Export request payload is required");
        }

        FormatEnum formatEnum = request.getFormat();
        if (formatEnum == null) {
            throw new InvalidFileException("Export format is required (CSV, TXT, or XLSX)");
        }
        String format = formatEnum.getValue();

        List<String> rawColumns = request.getColumns();
        if (rawColumns == null || rawColumns.isEmpty()) {
            throw new InvalidColumnException("At least one export column must be specified");
        }

        for (String col : rawColumns) {
            CustomerColumn.fromString(col);
        }

        ExportStrategy strategy = exportStrategies.stream()
                .filter(s -> s.supports(format))
                .findFirst()
                .orElseThrow(() -> new InvalidExportFormatException("Unsupported export format: '" + format +
                        "'. Supported formats are CSV, TXT, XLS, XLSX."));

        java.util.UUID exportId = java.util.UUID.randomUUID();
        String extension = strategy.getFileExtension();
        String message = "Request sent to generate file with extension " + extension;

        pt.planet.dto.ExportAsyncResponse response = new pt.planet.dto.ExportAsyncResponse();
        response.setExportId(exportId);
        response.setMessage(message);
        response.setFormat(format);
        response.setEmail(userEmail);

        if (executor != null) {
            executor.execute(() -> processExportInBackground(exportId, request, userEmail));
        } else {
            processExportInBackground(exportId, request, userEmail);
        }

        return response;
    }

    public void processExportInBackground(java.util.UUID exportId, ExportRequest request, String userEmail) {
        try {
            log.info("Starting background export for id: {}", exportId);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ExportMetadata metadata = exportCustomersToStream(request, out);
            byte[] data = out.toByteArray();

            String s3Key = "exports/" + metadata.filename();
            String bucket = (s3StorageService != null) ? s3StorageService.getBucket() : "customer-exports";
            String presignedUrl = "";

            if (s3StorageService != null) {
                s3StorageService.upload(s3Key, data, metadata.contentType());
                presignedUrl = s3StorageService.generatePresignedUrl(s3Key, null);
                log.info("Uploaded export {} to S3 key '{}'. Pre-signed URL generated.", exportId, s3Key);
            }

            pt.planet.dto.ExportNotificationMessage notification = new pt.planet.dto.ExportNotificationMessage(
                    exportId,
                    userEmail,
                    metadata.filename(),
                    request.getFormat().getValue(),
                    bucket,
                    s3Key,
                    presignedUrl,
                    metadata.totalRecords(),
                    Instant.now()
            );

            if (notificationProducer != null) {
                notificationProducer.sendExportNotification(notification);
                log.info("Dispatched export completion notification to queue for exportId: {}", exportId);
            }
        } catch (Exception e) {
            log.error("Failed to process background export for id {}: {}", exportId, e.getMessage(), e);
        }
    }

    @Observed(name = "file.export", contextualName = "export-customers")
    @Transactional(readOnly = true)
    public ExportResult exportCustomers(ExportRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportMetadata metadata = exportCustomersToStream(request, out);
        return new ExportResult(out.toByteArray(), metadata.contentType(), metadata.filename());
    }

    @Observed(name = "file.export.stream", contextualName = "export-customers-stream")
    @Transactional(readOnly = true)
    public ExportMetadata exportCustomersToStream(ExportRequest request, OutputStream outputStream) {
        if (request == null) {
            throw new InvalidFileException("Export request payload is required");
        }

        FormatEnum formatEnum = request.getFormat();
        if (formatEnum == null) {
            throw new InvalidFileException("Export format is required (CSV, TXT, or XLSX)");
        }
        String format = formatEnum.getValue();

        List<String> rawColumns = request.getColumns();
        if (rawColumns == null || rawColumns.isEmpty()) {
            throw new InvalidColumnException("At least one export column must be specified");
        }

        // Map and validate columns
        List<CustomerColumn> columns = new ArrayList<>();
        for (String col : rawColumns) {
            columns.add(CustomerColumn.fromString(col));
        }

        // Locate strategy
        ExportStrategy strategy = exportStrategies.stream()
                .filter(s -> s.supports(format))
                .findFirst()
                .orElseThrow(() -> new InvalidExportFormatException("Unsupported export format: '" + format +
                        "'. Supported formats are CSV, TXT, XLS, XLSX."));

        Instant startTime = Instant.now();
        AtomicInteger totalCount = new AtomicInteger(0);

        // Keyset (Cursor-Based) Pagination batch supplier:
        // Retrieves records in bounded chunks using B-Tree indexed ID seek (id > lastId).
        // Evicts entities from the Hibernate 1st-level cache (EntityManager) after each batch.
        CustomerBatchSupplier batchSupplier = batchConsumer -> {
            Long lastId = null;
            List<CustomerEntity> batch;
            do {
                if (lastId == null) {
                    batch = customerRepository.findAllByOrderByIdAsc(Limit.of(batchSize));
                } else {
                    batch = customerRepository.findByIdGreaterThanOrderByIdAsc(lastId, Limit.of(batchSize));
                }

                if (batch != null && !batch.isEmpty()) {
                    totalCount.addAndGet(batch.size());
                    batchConsumer.accept(batch);
                    lastId = batch.getLast().getId();
                    if (entityManager != null) {
                        entityManager.clear(); // Free memory in Hibernate persistence context
                    }
                }
            } while (batch != null && batch.size() == batchSize);
        };

        // Stream batches into the export strategy and write directly to outputStream
        strategy.export(batchSupplier, columns, outputStream);

        Duration duration = Duration.between(startTime, Instant.now());
        int count = totalCount.get();
        appMetricsService.recordExport(format, count, duration);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "customers_" + timestamp + strategy.getFileExtension();

        log.info("Exported {} customer records using keyset pagination as {} ({}) in {} ms",
                count, format, filename, duration.toMillis());

        return new ExportMetadata(strategy.getContentType(), filename, count);
    }
}
