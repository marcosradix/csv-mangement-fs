package pt.planet.exportfile;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;
import pt.planet.domain.CustomerEntity;
import pt.planet.exception.InvalidFileException;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class CsvExportStrategy implements ExportStrategy {

    @Override
    public boolean supports(String format) {
        return "CSV".equalsIgnoreCase(format);
    }

    @Override
    public byte[] export(List<CustomerEntity> customers, List<CustomerColumn> columns) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT)) {

            // Write headers
            List<String> headerNames = columns.stream().map(CustomerColumn::getHeaderName).toList();
            csvPrinter.printRecord(headerNames);

            // Write data rows
            for (CustomerEntity customer : customers) {
                List<String> values = columns.stream()
                        .map(col -> col.getValue(customer))
                        .toList();
                csvPrinter.printRecord(values);
            }

            csvPrinter.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new InvalidFileException("Error generating CSV export: " + e.getMessage(), e);
        }
    }

    @Override
    public String getContentType() {
        return "text/csv; charset=UTF-8";
    }

    @Override
    public String getFileExtension() {
        return ".csv";
    }
}
