package pt.planet.exportfile;

import org.springframework.stereotype.Component;
import pt.planet.domain.CustomerEntity;
import pt.planet.exception.InvalidFileException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class TxtExportStrategy implements ExportStrategy {

    @Override
    public boolean supports(String format) {
        return "TXT".equalsIgnoreCase(format);
    }

    @Override
    public byte[] export(List<CustomerEntity> customers, List<CustomerColumn> columns) {
        try {
            int numCols = columns.size();
            int[] colWidths = new int[numCols];

            // Header widths
            for (int i = 0; i < numCols; i++) {
                colWidths[i] = Math.max(columns.get(i).getHeaderName().length(), 4);
            }

            // Prepare row data and compute max widths
            List<List<String>> rowValues = new ArrayList<>(customers.size());
            for (CustomerEntity customer : customers) {
                List<String> row = new ArrayList<>(numCols);
                for (int i = 0; i < numCols; i++) {
                    String val = columns.get(i).getValue(customer);
                    row.add(val);
                    if (val.length() > colWidths[i]) {
                        colWidths[i] = val.length();
                    }
                }
                rowValues.add(row);
            }

            StringBuilder sb = new StringBuilder();

            // Format Header
            for (int i = 0; i < numCols; i++) {
                String header = columns.get(i).getHeaderName().toUpperCase();
                sb.append(String.format("%-" + colWidths[i] + "s", header));
                if (i < numCols - 1) {
                    sb.append(" | ");
                }
            }
            sb.append("\n");

            // Format Separator line
            int totalLineWidth = 0;
            for (int width : colWidths) {
                totalLineWidth += width;
            }
            totalLineWidth += (numCols - 1) * 3; // " | " separators
            sb.append("-".repeat(Math.max(1, totalLineWidth))).append("\n");

            // Format Data rows
            for (List<String> row : rowValues) {
                for (int i = 0; i < numCols; i++) {
                    sb.append(String.format("%-" + colWidths[i] + "s", row.get(i)));
                    if (i < numCols - 1) {
                        sb.append(" | ");
                    }
                }
                sb.append("\n");
            }

            return sb.toString().getBytes(StandardCharsets.UTF_8);
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
