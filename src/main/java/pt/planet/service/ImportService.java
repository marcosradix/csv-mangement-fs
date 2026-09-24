package pt.planet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import pt.planet.domain.CustomerEntity;
import pt.planet.domain.ImportEntity;
import pt.planet.domain.ImportErrorEntity;
import pt.planet.domain.ImportStatus;
import pt.planet.dto.GroupedImportErrorResponse;
import pt.planet.dto.ImportErrorResponse;
import pt.planet.dto.ImportResponse;
import pt.planet.exception.CustomerNotFoundException;
import pt.planet.exception.InvalidFileException;
import pt.planet.importfile.CsvParser;
import pt.planet.importfile.CustomerRecord;
import pt.planet.mapper.CustomerMapper;
import pt.planet.observability.AppMetricsService;
import pt.planet.repository.CustomerRepository;
import pt.planet.repository.ImportErrorRepository;
import pt.planet.repository.ImportRepository;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final CsvParser csvParser;
    private final CustomerRepository customerRepository;
    private final ImportRepository importRepository;
    private final ImportErrorRepository importErrorRepository;
    private final CustomerMapper customerMapper;
    private final AppMetricsService appMetricsService;

    @Transactional
    public List<ImportResponse> processImports(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new InvalidFileException("At least one CSV file must be provided for import");
        }

        List<ImportResponse> responses = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            responses.add(processImport(file));
        }
        return responses;
    }

    @Transactional
    public ImportResponse processImport(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Uploaded file is missing or empty");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "unknown.csv";
        }

        if (!originalFilename.toLowerCase().endsWith(".csv") &&
                (file.getContentType() != null && !file.getContentType().contains("csv")
                        && !file.getContentType().contains("text/plain"))) {
            throw new InvalidFileException("Only CSV files are supported for import. Received: " + originalFilename);
        }

        Instant startTime = Instant.now();
        UUID importId = UUID.randomUUID();

        CsvParser.ParseResult parseResult;
        try (InputStream is = file.getInputStream()) {
            parseResult = csvParser.parse(is);
        } catch (IOException e) {
            throw new InvalidFileException("Failed to read uploaded file: " + e.getMessage(), e);
        }

        Set<Integer> failedRows = new HashSet<>();
        List<ImportErrorEntity> errorsToPersist = new ArrayList<>();

        // Process row-level parse and validation errors
        for (CsvParser.RowError rowErr : parseResult.rowErrors()) {
            failedRows.add(rowErr.rowNumber());
            ImportErrorEntity errorEntity = new ImportErrorEntity(
                    importId,
                    originalFilename,
                    rowErr.rowNumber(),
                    rowErr.fieldName(),
                    rowErr.errorMessage(),
                    rowErr.rawData());
            errorsToPersist.add(errorEntity);
        }

        int successfulRecords = 0;
        // Process valid records with upsert logic
        for (CustomerRecord record : parseResult.validRecords()) {
            try {
                upsertCustomer(record);
                successfulRecords++;
            } catch (Exception ex) {
                log.warn("Failed to persist customer id {}: {}", record.id(), ex.getMessage());
                failedRows.add(record.rowNumber());
                ImportErrorEntity errorEntity = new ImportErrorEntity(
                        importId,
                        originalFilename,
                        record.rowNumber(),
                        "persistence",
                        "Database save failed: " + ex.getMessage(),
                        record.rawData());
                errorsToPersist.add(errorEntity);
            }
        }

        int failedRecords = failedRows.size();

        int totalRecords = parseResult.totalProcessedRows();

        // Determine final import status
        ImportStatus status;
        if (failedRecords == 0 && successfulRecords > 0) {
            status = ImportStatus.SUCCESS;
        } else if (successfulRecords > 0 && failedRecords > 0) {
            status = ImportStatus.PARTIAL_SUCCESS;
        } else {
            status = ImportStatus.FAILED;
        }

        ImportEntity importEntity = new ImportEntity(
                importId,
                originalFilename,
                status,
                totalRecords,
                successfulRecords,
                failedRecords);

        importRepository.save(importEntity);
        if (!errorsToPersist.isEmpty()) {
            List<ImportErrorEntity> savedErrors = importErrorRepository.saveAllAndFlush(errorsToPersist);
            logImportErrors(savedErrors);
        }

        Duration duration = Duration.between(startTime, Instant.now());
        appMetricsService.recordImport(status.name(), totalRecords, successfulRecords, failedRecords, duration);

        log.info("Completed import {} ({}) - Status: {}, Total: {}, Success: {}, Failed: {}",
                importId, originalFilename, status, totalRecords, successfulRecords, failedRecords);

        return customerMapper.toImportResponse(importEntity);
    }

    private void upsertCustomer(CustomerRecord record) {
        Optional<CustomerEntity> existingOpt = customerRepository.findById(record.id());
        CustomerEntity customer;

        if (existingOpt.isPresent()) {
            customer = existingOpt.get();
            // Update fields (merge updates from incoming file)
            if (record.name() != null && !record.name().isBlank()) {
                customer.setName(record.name());
            }
            if (record.email() != null && !record.email().isBlank()) {
                customer.setEmail(record.email());
            }
            if (ObjectUtils.isNotEmpty(record.age())) {
                customer.setAge(record.age());
            }
            if (record.country() != null && !record.country().isBlank()) {
                customer.setCountry(record.country());
            }
            if (record.phone() != null && !record.phone().isBlank()) {
                customer.setPhone(record.phone());
            }
        } else {
            customer = new CustomerEntity(
                    record.id(),
                    record.name(),
                    record.email(),
                    record.age(),
                    record.country(),
                    record.phone());
        }

        customerRepository.saveAndFlush(customer);
    }

    @Transactional(readOnly = true)
    public ImportResponse getImportById(UUID importId) {
        ImportEntity entity = importRepository.findById(importId)
                .orElseThrow(() -> new CustomerNotFoundException("Import execution not found for id: " + importId));
        return customerMapper.toImportResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<GroupedImportErrorResponse> getImportErrors(UUID importId) {
        // Ensure import exists
        if (!importRepository.existsById(importId)) {
            throw new CustomerNotFoundException("Import execution not found for id: " + importId);
        }
        List<ImportErrorEntity> errors = importErrorRepository.findByImportIdOrderByRowNumberAsc(importId);
        return customerMapper.toGroupedErrorResponseList(errors);
    }

    private void logImportErrors(List<ImportErrorEntity> errors) {
        for (ImportErrorEntity err : errors) {
            log.error(
                    "Import error line: id={}, importId={}, filename='{}', rowNumber={}, fieldName='{}', errorMessage='{}', rawData='{}'",
                    err.getId(), err.getImportId(), err.getFilename(), err.getRowNumber(), err.getFieldName(), err.getErrorMessage(),
                    err.getRawData());
        }
    }
}
