package pt.planet.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.planet.domain.CustomerEntity;
import pt.planet.dto.ExportRequest;
import pt.planet.dto.ExportRequest.FormatEnum;
import pt.planet.exception.InvalidColumnException;
import pt.planet.exception.InvalidExportFormatException;
import pt.planet.exception.InvalidFileException;
import pt.planet.exportfile.CustomerColumn;
import pt.planet.exportfile.CsvExportStrategy;
import pt.planet.exportfile.ExportStrategy;
import pt.planet.exportfile.TxtExportStrategy;
import pt.planet.exportfile.XlsxExportStrategy;
import pt.planet.observability.AppMetricsService;
import pt.planet.repository.CustomerRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AppMetricsService appMetricsService;

    @Mock
    private ExportStrategy csvStrategy;

    @Mock
    private ExportStrategy txtStrategy;

    @Mock
    private ExportStrategy xlsxStrategy;

    @Captor
    private ArgumentCaptor<List<CustomerColumn>> columnsCaptor;

    private ExportService exportService;
    private List<CustomerEntity> mockCustomers;

    @BeforeEach
    void setUp() {
        exportService = new ExportService(
                List.of(csvStrategy, txtStrategy, xlsxStrategy),
                customerRepository,
                appMetricsService
        );

        // Prepare mock customer data
        CustomerEntity c1 = new CustomerEntity(1L, "Alice Johnson", "alice@example.com", 29, "Portugal", "+351911222333");
        CustomerEntity c2 = new CustomerEntity(2L, "Bob Smith", "bob@example.com", 42, "Spain", "+34600112233");
        CustomerEntity c3 = new CustomerEntity(3L, "Carlos Silva", "carlos@example.com", null, "Brazil", null);
        mockCustomers = List.of(c1, c2, c3);
    }

    @Test
    @DisplayName("Should successfully export mock customers to CSV using matching strategy")
    void testExportCustomersCsvWithMockData() {
        // Given
        byte[] expectedCsvBytes = "id,name,email\n1,Alice Johnson,alice@example.com\n".getBytes(StandardCharsets.UTF_8);
        when(csvStrategy.supports("CSV")).thenReturn(true);
        when(csvStrategy.export(eq(mockCustomers), any())).thenReturn(expectedCsvBytes);
        when(csvStrategy.getContentType()).thenReturn("text/csv");
        when(csvStrategy.getFileExtension()).thenReturn(".csv");
        when(customerRepository.findAllByOrderByIdAsc()).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.CSV, List.of("id", "name", "email"));

        // When
        ExportService.ExportResult result = exportService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.data()).isEqualTo(expectedCsvBytes);
        assertThat(result.contentType()).isEqualTo("text/csv");
        assertThat(result.filename()).matches("^customers_\\d{8}_\\d{6}\\.csv$");

        verify(customerRepository).findAllByOrderByIdAsc();
        verify(csvStrategy).export(eq(mockCustomers), columnsCaptor.capture());
        assertThat(columnsCaptor.getValue()).containsExactly(
                CustomerColumn.ID,
                CustomerColumn.NAME,
                CustomerColumn.EMAIL
        );

        verify(appMetricsService).recordExport(eq("CSV"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("Should successfully export mock customers to TXT using matching strategy")
    void testExportCustomersTxtWithMockData() {
        // Given
        byte[] expectedTxtBytes = "NAME         EMAIL\nAlice        alice@example.com\n".getBytes(StandardCharsets.UTF_8);
        when(txtStrategy.supports("TXT")).thenReturn(true);
        when(txtStrategy.export(eq(mockCustomers), any())).thenReturn(expectedTxtBytes);
        when(txtStrategy.getContentType()).thenReturn("text/plain");
        when(txtStrategy.getFileExtension()).thenReturn(".txt");
        when(customerRepository.findAllByOrderByIdAsc()).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.TXT, List.of("name", "email"));

        // When
        ExportService.ExportResult result = exportService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.data()).isEqualTo(expectedTxtBytes);
        assertThat(result.contentType()).isEqualTo("text/plain");
        assertThat(result.filename()).matches("^customers_\\d{8}_\\d{6}\\.txt$");

        verify(customerRepository).findAllByOrderByIdAsc();
        verify(txtStrategy).export(eq(mockCustomers), columnsCaptor.capture());
        assertThat(columnsCaptor.getValue()).containsExactly(CustomerColumn.NAME, CustomerColumn.EMAIL);

        verify(appMetricsService).recordExport(eq("TXT"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("Should successfully export mock customers to XLSX using matching strategy")
    void testExportCustomersXlsxWithMockData() {
        // Given
        byte[] expectedXlsxBytes = new byte[]{0x50, 0x4B, 0x03, 0x04}; // Zip/Xlsx header
        when(xlsxStrategy.supports("XLSX")).thenReturn(true);
        when(xlsxStrategy.export(eq(mockCustomers), any())).thenReturn(expectedXlsxBytes);
        when(xlsxStrategy.getContentType()).thenReturn("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        when(xlsxStrategy.getFileExtension()).thenReturn(".xlsx");
        when(customerRepository.findAllByOrderByIdAsc()).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.XLSX, List.of("id", "name", "country", "phone"));

        // When
        ExportService.ExportResult result = exportService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.data()).isEqualTo(expectedXlsxBytes);
        assertThat(result.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(result.filename()).matches("^customers_\\d{8}_\\d{6}\\.xlsx$");

        verify(customerRepository).findAllByOrderByIdAsc();
        verify(xlsxStrategy).export(eq(mockCustomers), columnsCaptor.capture());
        assertThat(columnsCaptor.getValue()).containsExactly(
                CustomerColumn.ID,
                CustomerColumn.NAME,
                CustomerColumn.COUNTRY,
                CustomerColumn.PHONE
        );

        verify(appMetricsService).recordExport(eq("XLSX"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("Should export successfully when repository returns empty customer list")
    void testExportWithEmptyCustomerList() {
        // Given
        when(csvStrategy.supports("CSV")).thenReturn(true);
        when(csvStrategy.export(eq(Collections.emptyList()), any())).thenReturn("id,name\n".getBytes(StandardCharsets.UTF_8));
        when(csvStrategy.getContentType()).thenReturn("text/csv");
        when(csvStrategy.getFileExtension()).thenReturn(".csv");
        when(customerRepository.findAllByOrderByIdAsc()).thenReturn(Collections.emptyList());

        ExportRequest request = new ExportRequest(FormatEnum.CSV, List.of("id", "name"));

        // When
        ExportService.ExportResult result = exportService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.contentType()).isEqualTo("text/csv");
        verify(appMetricsService).recordExport(eq("CSV"), eq(0), any(Duration.class));
    }

    @Test
    @DisplayName("Should integrate with real strategies and transform mock customer entities into actual CSV content")
    void testExportWithRealCsvStrategyAndMockData() {
        // Given real strategies wired with mock repository and metrics
        ExportService serviceWithRealStrategies = new ExportService(
                List.of(new CsvExportStrategy(), new TxtExportStrategy(), new XlsxExportStrategy()),
                customerRepository,
                appMetricsService
        );

        when(customerRepository.findAllByOrderByIdAsc()).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.CSV, List.of("id", "name", "country"));

        // When
        ExportService.ExportResult result = serviceWithRealStrategies.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.contentType()).isEqualTo("text/csv; charset=UTF-8");

        String csvString = new String(result.data(), StandardCharsets.UTF_8);
        assertThat(csvString).contains("id,name,country");
        assertThat(csvString).contains("1,Alice Johnson,Portugal");
        assertThat(csvString).contains("2,Bob Smith,Spain");
        assertThat(csvString).contains("3,Carlos Silva,Brazil");

        verify(appMetricsService).recordExport(eq("CSV"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("Should throw InvalidFileException when request payload is null")
    void testExportThrowsWhenRequestIsNull() {
        assertThatThrownBy(() -> exportService.exportCustomers(null))
                .isInstanceOf(InvalidFileException.class)
                .hasMessage("Export request payload is required");

        verifyNoInteractions(customerRepository);
        verifyNoInteractions(appMetricsService);
    }

    @Test
    @DisplayName("Should throw InvalidFileException when format is null")
    void testExportThrowsWhenFormatIsNull() {
        ExportRequest request = new ExportRequest();
        request.setFormat(null);
        request.setColumns(List.of("name"));

        assertThatThrownBy(() -> exportService.exportCustomers(request))
                .isInstanceOf(InvalidFileException.class)
                .hasMessage("Export format is required (CSV, TXT, or XLSX)");

        verifyNoInteractions(customerRepository);
        verifyNoInteractions(appMetricsService);
    }

    @Test
    @DisplayName("Should throw InvalidColumnException when columns list is null or empty")
    void testExportThrowsWhenColumnsListIsNullOrEmpty() {
        ExportRequest requestNullColumns = new ExportRequest(FormatEnum.CSV, null);
        assertThatThrownBy(() -> exportService.exportCustomers(requestNullColumns))
                .isInstanceOf(InvalidColumnException.class)
                .hasMessage("At least one export column must be specified");

        ExportRequest requestEmptyColumns = new ExportRequest(FormatEnum.CSV, Collections.emptyList());
        assertThatThrownBy(() -> exportService.exportCustomers(requestEmptyColumns))
                .isInstanceOf(InvalidColumnException.class)
                .hasMessage("At least one export column must be specified");

        verifyNoInteractions(customerRepository);
        verifyNoInteractions(appMetricsService);
    }

    @Test
    @DisplayName("Should throw InvalidColumnException when an unsupported column is requested")
    void testExportThrowsWhenColumnIsUnsupported() {
        ExportRequest request = new ExportRequest(FormatEnum.CSV, List.of("id", "unsupported_column"));

        assertThatThrownBy(() -> exportService.exportCustomers(request))
                .isInstanceOf(InvalidColumnException.class)
                .hasMessageContaining("Unsupported export column: 'unsupported_column'");

        verifyNoInteractions(customerRepository);
        verifyNoInteractions(appMetricsService);
    }

    @Test
    @DisplayName("Should throw InvalidExportFormatException when no strategy supports the requested format")
    void testExportThrowsWhenNoStrategySupportsFormat() {
        // Only CSV strategy provided
        ExportService serviceOnlyCsv = new ExportService(
                List.of(csvStrategy),
                customerRepository,
                appMetricsService
        );

        when(csvStrategy.supports("TXT")).thenReturn(false);

        ExportRequest request = new ExportRequest(FormatEnum.TXT, List.of("id", "name"));

        assertThatThrownBy(() -> serviceOnlyCsv.exportCustomers(request))
                .isInstanceOf(InvalidExportFormatException.class)
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("Unsupported export format: 'TXT'");

        verifyNoInteractions(customerRepository);
        verifyNoInteractions(appMetricsService);
    }
}
