package pt.planet.exportfile;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;
import pt.planet.domain.CustomerEntity;
import pt.planet.exception.InvalidFileException;

import java.io.OutputStream;
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
    public void export(CustomerBatchSupplier customerSupplier, List<CustomerColumn> columns,
            OutputStream outputStream) {
        try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT)) {

            // Write headers
            List<String> headerNames = columns.stream().map(CustomerColumn::getHeaderName).toList();
            csvPrinter.printRecord(headerNames);

            // Write data rows batch by batch from keyset supplier
            customerSupplier.fetchBatches(batch -> {
                try {
                    for (CustomerEntity customer : batch) {
                        List<String> values = columns.stream()
                                .map(col -> col.getValue(customer))
                                .toList();
                        csvPrinter.printRecord(values);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            csvPrinter.flush();
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
