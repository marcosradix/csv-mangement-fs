package pt.planet.service;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.planet.domain.CustomerEntity;
import pt.planet.dto.ExportRequest;
import pt.planet.dto.ExportRequest.FormatEnum;
import pt.planet.exception.InvalidColumnException;
import pt.planet.exception.InvalidExportFormatException;
import pt.planet.exception.InvalidFileException;
import pt.planet.exportfile.CustomerColumn;
import pt.planet.exportfile.ExportStrategy;
import pt.planet.observability.AppMetricsService;
import pt.planet.repository.CustomerRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final List<ExportStrategy> exportStrategies;
    private final CustomerRepository customerRepository;
    private final AppMetricsService appMetricsService;

    public record ExportResult(
            byte[] data,
            String contentType,
            String filename
    ) {}

    @Observed(name = "file.export", contextualName = "export-customers")
    @Transactional(readOnly = true)
    public ExportResult exportCustomers(ExportRequest request) {
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
        List<CustomerEntity> customers = customerRepository.findAllByOrderByIdAsc();

        byte[] exportedBytes = strategy.export(customers, columns);

        Duration duration = Duration.between(startTime, Instant.now());
        appMetricsService.recordExport(format, customers.size(), duration);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "customers_" + timestamp + strategy.getFileExtension();

        log.info("Exported {} customer records as {} ({}) in {} ms",
                customers.size(), format, filename, duration.toMillis());

        return new ExportResult(exportedBytes, strategy.getContentType(), filename);
    }
}
