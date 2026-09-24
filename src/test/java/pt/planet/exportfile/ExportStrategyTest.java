package pt.planet.exportfile;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pt.planet.domain.CustomerEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExportStrategyTest {

    private List<CustomerEntity> sampleCustomers;

    @BeforeEach
    void setUp() {
        CustomerEntity c1 = new CustomerEntity(1L, "John Smith", "john@example.com", 35, "Portugal", "+351910000000");
        CustomerEntity c2 = new CustomerEntity(2L, "Jane Doe", "jane@example.com", 28, "Spain", null);
        sampleCustomers = List.of(c1, c2);
    }

    @Test
    @DisplayName("CsvExportStrategy should export requested columns in exact order")
    void testCsvExport() {
        CsvExportStrategy strategy = new CsvExportStrategy();
        assertThat(strategy.supports("CSV")).isTrue();
        assertThat(strategy.supports("csv")).isTrue();
        assertThat(strategy.supports("TXT")).isFalse();

        List<CustomerColumn> columns = List.of(CustomerColumn.ID, CustomerColumn.NAME, CustomerColumn.EMAIL, CustomerColumn.COUNTRY);
        byte[] bytes = strategy.export(sampleCustomers, columns);
        String csvContent = new String(bytes, StandardCharsets.UTF_8);

        String[] lines = csvContent.split("\r?\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("id,name,email,country");
        assertThat(lines[1]).isEqualTo("1,John Smith,john@example.com,Portugal");
        assertThat(lines[2]).isEqualTo("2,Jane Doe,jane@example.com,Spain");
    }

    @Test
    @DisplayName("TxtExportStrategy should export aligned text with requested columns")
    void testTxtExport() {
        TxtExportStrategy strategy = new TxtExportStrategy();
        assertThat(strategy.supports("TXT")).isTrue();

        List<CustomerColumn> columns = List.of(CustomerColumn.NAME, CustomerColumn.EMAIL);
        byte[] bytes = strategy.export(sampleCustomers, columns);
        String text = new String(bytes, StandardCharsets.UTF_8);

        assertThat(text).contains("NAME");
        assertThat(text).contains("EMAIL");
        assertThat(text).contains("John Smith");
        assertThat(text).contains("john@example.com");
        assertThat(text).contains("Jane Doe");
        assertThat(text).contains("jane@example.com");
    }

    @Test
    @DisplayName("XlsxExportStrategy should create valid Excel workbook with correct cells")
    void testXlsxExport() throws Exception {
        XlsxExportStrategy strategy = new XlsxExportStrategy();
        assertThat(strategy.supports("XLSX")).isTrue();
        assertThat(strategy.supports("XLS")).isTrue();

        List<CustomerColumn> columns = List.of(CustomerColumn.ID, CustomerColumn.NAME, CustomerColumn.AGE);
        byte[] bytes = strategy.export(sampleCustomers, columns);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Customers");
            assertThat(sheet).isNotNull();

            // Header row
            Row headerRow = sheet.getRow(0);
            assertThat(headerRow.getCell(0).getStringCellValue()).isEqualTo("ID");
            assertThat(headerRow.getCell(1).getStringCellValue()).isEqualTo("NAME");
            assertThat(headerRow.getCell(2).getStringCellValue()).isEqualTo("AGE");

            // First data row
            Row row1 = sheet.getRow(1);
            assertThat((long) row1.getCell(0).getNumericCellValue()).isEqualTo(1L);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("John Smith");
            assertThat((int) row1.getCell(2).getNumericCellValue()).isEqualTo(35);
        }
    }
}
