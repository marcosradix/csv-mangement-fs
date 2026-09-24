package pt.planet.importfile;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import pt.planet.exception.InvalidFileException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class CsvParser {

    private final CsvHeaderAnalyzer headerAnalyzer;
    private final CustomerValidator validator;

    public CsvParser(CsvHeaderAnalyzer headerAnalyzer, CustomerValidator validator) {
        this.headerAnalyzer = headerAnalyzer;
        this.validator = validator;
    }

    public record RowError(
            int rowNumber,
            String fieldName,
            String errorMessage,
            String rawData) {
    }

    public record ParseResult(
            List<CustomerRecord> validRecords,
            List<RowError> rowErrors,
            int totalProcessedRows) {
    }

    public ParseResult parse(InputStream inputStream) {
        if (inputStream == null) {
            throw new InvalidFileException("Input stream is null");
        }

        List<CustomerRecord> validRecords = new ArrayList<>();
        List<RowError> rowErrors = new ArrayList<>();
        int totalRows = 0;

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

            CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .setIgnoreEmptyLines(true)
                    .build();

            CSVParser parser = csvFormat.parse(reader);

            List<String> headerNames = parser.getHeaderNames();
            if (headerNames == null || headerNames.isEmpty()) {
                throw new InvalidFileException("CSV file contains no header row or is empty");
            }

            CsvHeaderAnalyzer.HeaderAnalysisResult analysis = headerAnalyzer.analyze(headerNames);

            String idHeader = analysis.getActualHeader(CsvHeaderAnalyzer.COLUMN_ID);
            String nameHeader = analysis.getActualHeader(CsvHeaderAnalyzer.COLUMN_NAME);
            String emailHeader = analysis.getActualHeader(CsvHeaderAnalyzer.COLUMN_EMAIL);
            String ageHeader = analysis.getActualHeader(CsvHeaderAnalyzer.COLUMN_AGE);
            String countryHeader = analysis.getActualHeader(CsvHeaderAnalyzer.COLUMN_COUNTRY);
            String phoneHeader = analysis.getActualHeader(CsvHeaderAnalyzer.COLUMN_PHONE);

            for (CSVRecord record : parser) {
                totalRows++;
                int rowNumber = (int) record.getRecordNumber() + 1; // +1 to account for 1-based header row
                String rawLine = record.values() != null ? String.join(",", record.values()) : "";

                try {
                    String rawId = getFieldValue(record, idHeader);
                    String rawName = getFieldValue(record, nameHeader);
                    String rawEmail = getFieldValue(record, emailHeader);
                    String rawAge = getFieldValue(record, ageHeader);
                    String rawCountry = getFieldValue(record, countryHeader);
                    String rawPhone = getFieldValue(record, phoneHeader);

                    ValidationResult validation = validator.validate(rawId, rawName, rawEmail, rawAge, rawCountry,
                            rawPhone);

                    if (validation.isValid()) {
                        Long id = Long.parseLong(rawId.trim());
                        String name = rawName.trim();
                        String email = (rawEmail != null && !rawEmail.trim().isEmpty()) ? rawEmail.trim() : null;
                        Integer age = (rawAge != null && !rawAge.trim().isEmpty()) ? Integer.parseInt(rawAge.trim())
                                : null;
                        String country = (rawCountry != null && !rawCountry.trim().isEmpty()) ? rawCountry.trim()
                                : null;
                        String phone = (rawPhone != null && !rawPhone.trim().isEmpty()) ? rawPhone.trim() : null;

                        validRecords.add(new CustomerRecord(id, name, email, age, country, phone, rowNumber, rawLine));
                    } else {
                        for (ValidationResult.ValidationError err : validation.getErrors()) {
                            rowErrors.add(new RowError(rowNumber, err.fieldName(), err.errorMessage(), rawLine));
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Error processing row {}: {}", rowNumber, ex.getMessage());
                    rowErrors.add(
                            new RowError(rowNumber, "record", "Failed to process record: " + ex.getMessage(), rawLine));
                }
            }

            if (totalRows == 0) {
                throw new InvalidFileException("CSV file contains headers but no data records");
            }

        } catch (InvalidFileException e) {
            throw e;
        } catch (IOException e) {
            throw new InvalidFileException("Error reading CSV file input stream: " + e.getMessage(), e);
        }

        return new ParseResult(validRecords, rowErrors, totalRows);
    }

    private String getFieldValue(CSVRecord record, String header) {
        if (header == null) {
            return null;
        }
        if (record.isSet(header)) {
            return record.get(header);
        }
        return null;
    }
}
