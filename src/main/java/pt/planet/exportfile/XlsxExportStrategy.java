package pt.planet.exportfile;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import pt.planet.domain.CustomerEntity;
import pt.planet.exception.InvalidFileException;

import java.io.ByteArrayOutputStream;
import java.util.List;

@Component
public class XlsxExportStrategy implements ExportStrategy {

    @Override
    public boolean supports(String format) {
        return "XLSX".equalsIgnoreCase(format) || "XLS".equalsIgnoreCase(format);
    }

    @Override
    public byte[] export(List<CustomerEntity> customers, List<CustomerColumn> columns) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Customers");

            // Header Style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Create Header Row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i).getHeaderName().toUpperCase());
                cell.setCellStyle(headerStyle);
            }

            // Create Data Rows
            int rowIndex = 1;
            for (CustomerEntity customer : customers) {
                Row row = sheet.createRow(rowIndex++);
                for (int colIndex = 0; colIndex < columns.size(); colIndex++) {
                    CustomerColumn col = columns.get(colIndex);
                    Cell cell = row.createCell(colIndex);
                    String value = col.getValue(customer);

                    // Numeric formatting for ID and AGE if valid
                    if (col == CustomerColumn.ID && customer.getId() != null) {
                        cell.setCellValue(customer.getId());
                    } else if (col == CustomerColumn.AGE && customer.getAge() != null) {
                        cell.setCellValue(customer.getAge());
                    } else {
                        cell.setCellValue(value);
                    }
                }
            }

            // Auto-size columns
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new InvalidFileException("Error generating Excel export: " + e.getMessage(), e);
        }
    }

    @Override
    public String getContentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String getFileExtension() {
        return ".xlsx";
    }
}
