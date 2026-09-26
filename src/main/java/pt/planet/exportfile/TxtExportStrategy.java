package pt.planet.exportfile;

import org.springframework.stereotype.Component;
import pt.planet.domain.CustomerEntity;
import pt.planet.exception.InvalidFileException;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class TxtExportStrategy implements ExportStrategy {

    @Override
    public boolean supports(String format) {
        return "TXT".equalsIgnoreCase(format);
    }

    @Override
    public void export(CustomerBatchSupplier customerSupplier, List<CustomerColumn> columns,
            java.io.OutputStream outputStream) {
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            int numCols = columns.size();
            int[] colWidths = new int[numCols];

            // Header and column default widths
            for (int i = 0; i < numCols; i++) {
                colWidths[i] = Math.max(columns.get(i).getHeaderName().length(), columns.get(i).getDefaultWidth());
            }

            // Format Header
            for (int i = 0; i < numCols; i++) {
                String header = columns.get(i).getHeaderName().toUpperCase();
                writer.write(String.format("%-" + colWidths[i] + "s", header));
                if (i < numCols - 1) {
                    writer.write(" | ");
                }
            }
            writer.write("\n");

            // Format Separator line
            int totalLineWidth = 0;
            for (int width : colWidths) {
                totalLineWidth += width;
            }
            totalLineWidth += (numCols - 1) * 3; // " | " separators
            writer.write("-".repeat(Math.max(1, totalLineWidth)));
            writer.write("\n");

            // Stream data rows batch by batch from keyset supplier
            customerSupplier.fetchBatches(batch -> {
                try {
                    for (CustomerEntity customer : batch) {
                        for (int i = 0; i < numCols; i++) {
                            String val = columns.get(i).getValue(customer);
                            writer.write(String.format("%-" + colWidths[i] + "s", val));
                            if (i < numCols - 1) {
                                writer.write(" | ");
                            }
                        }
                        writer.write("\n");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            writer.flush();
        } catch (Exception e) {
            throw new InvalidFileException("Error generating TXT export: " + e.getMessage(), e);
        }
    }

    @Override
    public String getContentType() {
        return "text/plain; charset=UTF-8";
    }

    @Override
    public String getFileExtension() {
        return ".txt";
    }
}
