package pt.planet.importfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pt.planet.exception.InvalidFileException;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CsvHeaderAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CsvHeaderAnalyzer.class);

    public static final String COLUMN_ID = "id";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_EMAIL = "email";
    public static final String COLUMN_AGE = "age";
    public static final String COLUMN_COUNTRY = "country";
    public static final String COLUMN_PHONE = "phone";

    private static final Set<String> KNOWN_COLUMNS = Set.of(
            COLUMN_ID, COLUMN_NAME, COLUMN_EMAIL, COLUMN_AGE, COLUMN_COUNTRY, COLUMN_PHONE
    );

    public record HeaderAnalysisResult(
            Map<String, String> normalizedToActualHeader,
            Set<String> unknownHeaders
    ) {
        public String getActualHeader(String normalized) {
            return normalizedToActualHeader.get(normalized.toLowerCase());
        }

        public boolean hasColumn(String normalized) {
            return normalizedToActualHeader.containsKey(normalized.toLowerCase());
        }
    }

    public HeaderAnalysisResult analyze(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            throw new InvalidFileException("CSV file contains no header row or is empty");
        }

        Map<String, String> normalizedMap = new HashMap<>();
        Set<String> unknown = new HashSet<>();

        for (String rawHeader : headers) {
            if (rawHeader == null) continue;
            String normalized = rawHeader.trim().toLowerCase();
            if (KNOWN_COLUMNS.contains(normalized)) {
                normalizedMap.put(normalized, rawHeader.trim());
            } else {
                unknown.add(rawHeader.trim());
                log.info("Detected unknown CSV header column: '{}'", rawHeader.trim());
            }
        }

        // Validate required headers
        if (!normalizedMap.containsKey(COLUMN_ID)) {
            throw new InvalidFileException("CSV file is missing required header: 'id'");
        }
        if (!normalizedMap.containsKey(COLUMN_NAME)) {
            throw new InvalidFileException("CSV file is missing required header: 'name'");
        }

        return new HeaderAnalysisResult(Collections.unmodifiableMap(normalizedMap), Collections.unmodifiableSet(unknown));
    }
}
