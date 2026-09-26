package pt.planet.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import pt.planet.domain.CustomerEntity;
import pt.planet.dto.ExportRequest;
import pt.planet.dto.ExportRequest.FormatEnum;
import pt.planet.dto.ExportResult;
import pt.planet.exception.InvalidColumnException;
import pt.planet.exception.InvalidExportFormatException;
import pt.planet.exception.InvalidFileException;
import pt.planet.exportfile.CustomerBatchSupplier;
import pt.planet.exportfile.CustomerColumn;
import pt.planet.exportfile.CsvExportStrategy;
import pt.planet.exportfile.ExportStrategy;
import pt.planet.exportfile.TxtExportStrategy;
import pt.planet.exportfile.XlsxExportStrategy;
import pt.planet.observability.AppMetricsService;
import pt.planet.repository.CustomerRepository;

import java.io.OutputStream;
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
    private EntityManager entityManager;

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
                appMetricsService,
                entityManager,
                1000
        );

        // Prepare mock customer data
        CustomerEntity c1 = new CustomerEntity(1L, "Alice Johnson", "alice@example.com", 29, "Portugal", "+351911222333");
        CustomerEntity c2 = new CustomerEntity(2L, "Bob Smith", "bob@example.com", 42, "Spain", "+34600112233");
        CustomerEntity c3 = new CustomerEntity(3L, "Carlos Silva", "carlos@example.com", null, "Brazil", null);
        mockCustomers = List.of(c1, c2, c3);
    }

    @Test
    @DisplayName("Should successfully export mock customers to CSV using matching strategy with Keyset pagination")
    void testExportCustomersCsvWithMockData() {
        // Given
        byte[] expectedCsvBytes = "id,name,email\n1,Alice Johnson,alice@example.com\n".getBytes(StandardCharsets.UTF_8);
        when(csvStrategy.supports("CSV")).thenReturn(true);
        doAnswer(invocation -> {
            CustomerBatchSupplier supplier = invocation.getArgument(0);
            OutputStream os = invocation.getArgument(2);
            supplier.fetchBatches(batch -> {});
            os.write(expectedCsvBytes);
            return null;
        }).when(csvStrategy).export(any(CustomerBatchSupplier.class), columnsCaptor.capture(), any(OutputStream.class));

        when(csvStrategy.getContentType()).thenReturn("text/csv");
        when(csvStrategy.getFileExtension()).thenReturn(".csv");
        when(customerRepository.findAllByOrderByIdAsc(Limit.of(1000))).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.CSV, List.of("id", "name", "email"));

        // When
        ExportResult result = exportService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.data()).isEqualTo(expectedCsvBytes);
        assertThat(result.contentType()).isEqualTo("text/csv");
        assertThat(result.filename()).matches("^customers_\\d{8}_\\d{6}\\.csv$");

        verify(customerRepository).findAllByOrderByIdAsc(Limit.of(1000));
        verify(entityManager).clear();
        assertThat(columnsCaptor.getValue()).containsExactly(
                CustomerColumn.ID,
                CustomerColumn.NAME,
                CustomerColumn.EMAIL
        );

        verify(appMetricsService).recordExport(eq("CSV"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("Should traverse multiple batches using Keyset (Cursor-Based) Pagination and clear persistence context")
    void testKeysetPaginationMultiBatchTraversal() {
        // Given service with batchSize = 2
        ExportService batchingService = new ExportService(
                List.of(new CsvExportStrategy()),
                customerRepository,
                appMetricsService,
                entityManager,
                2
        );

        CustomerEntity c1 = mockCustomers.get(0); // id: 1
        CustomerEntity c2 = mockCustomers.get(1); // id: 2
        CustomerEntity c3 = mockCustomers.get(2); // id: 3

        // Batch 1 (initial keyset query): returns 2 records, lastId = 2
        when(customerRepository.findAllByOrderByIdAsc(Limit.of(2))).thenReturn(List.of(c1, c2));
        // Batch 2 (subsequent keyset query: id > 2): returns 1 record, size < 2 -> termination
        when(customerRepository.findByIdGreaterThanOrderByIdAsc(2L, Limit.of(2))).thenReturn(List.of(c3));

        ExportRequest request = new ExportRequest(FormatEnum.CSV, List.of("id", "name"));

        // When
        ExportResult result = batchingService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        String csv = new String(result.data(), StandardCharsets.UTF_8);
        assertThat(csv).contains("1,Alice Johnson");
        assertThat(csv).contains("2,Bob Smith");
        assertThat(csv).contains("3,Carlos Silva");

        // Verify keyset queries were executed in order
        verify(customerRepository).findAllByOrderByIdAsc(Limit.of(2));
        verify(customerRepository).findByIdGreaterThanOrderByIdAsc(2L, Limit.of(2));

        // Verify Hibernate 1st-level cache was cleared for each batch (2 batches = 2 clears)
        verify(entityManager, times(2)).clear();

        // Verify metrics record all 3 exported records
        verify(appMetricsService).recordExport(eq("CSV"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("Should successfully export mock customers to TXT using matching strategy")
    void testExportCustomersTxtWithMockData() {
        // Given
        byte[] expectedTxtBytes = "NAME         EMAIL\nAlice        alice@example.com\n".getBytes(StandardCharsets.UTF_8);
        when(txtStrategy.supports("TXT")).thenReturn(true);
        doAnswer(invocation -> {
            CustomerBatchSupplier supplier = invocation.getArgument(0);
            OutputStream os = invocation.getArgument(2);
            supplier.fetchBatches(batch -> {});
            os.write(expectedTxtBytes);
            return null;
        }).when(txtStrategy).export(any(CustomerBatchSupplier.class), columnsCaptor.capture(), any(OutputStream.class));

        when(txtStrategy.getContentType()).thenReturn("text/plain");
        when(txtStrategy.getFileExtension()).thenReturn(".txt");
        when(customerRepository.findAllByOrderByIdAsc(Limit.of(1000))).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.TXT, List.of("name", "email"));

        // When
        ExportResult result = exportService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.data()).isEqualTo(expectedTxtBytes);
        assertThat(result.contentType()).isEqualTo("text/plain");
        assertThat(result.filename()).matches("^customers_\\d{8}_\\d{6}\\.txt$");

        verify(customerRepository).findAllByOrderByIdAsc(Limit.of(1000));
        verify(entityManager).clear();
        assertThat(columnsCaptor.getValue()).containsExactly(CustomerColumn.NAME, CustomerColumn.EMAIL);

        verify(appMetricsService).recordExport(eq("TXT"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("Should successfully export mock customers to XLSX using matching strategy")
    void testExportCustomersXlsxWithMockData() {
        // Given
        byte[] expectedXlsxBytes = new byte[]{0x50, 0x4B, 0x03, 0x04}; // Zip/Xlsx header
        when(xlsxStrategy.supports("XLSX")).thenReturn(true);
        doAnswer(invocation -> {
            CustomerBatchSupplier supplier = invocation.getArgument(0);
            OutputStream os = invocation.getArgument(2);
            supplier.fetchBatches(batch -> {});
            os.write(expectedXlsxBytes);
            return null;
        }).when(xlsxStrategy).export(any(CustomerBatchSupplier.class), columnsCaptor.capture(), any(OutputStream.class));

        when(xlsxStrategy.getContentType()).thenReturn("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        when(xlsxStrategy.getFileExtension()).thenReturn(".xlsx");
        when(customerRepository.findAllByOrderByIdAsc(Limit.of(1000))).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.XLSX, List.of("id", "name", "country", "phone"));

        // When
        ExportResult result = exportService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.data()).isEqualTo(expectedXlsxBytes);
        assertThat(result.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(result.filename()).matches("^customers_\\d{8}_\\d{6}\\.xlsx$");

        verify(customerRepository).findAllByOrderByIdAsc(Limit.of(1000));
        verify(entityManager).clear();
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
        doAnswer(invocation -> {
            CustomerBatchSupplier supplier = invocation.getArgument(0);
            OutputStream os = invocation.getArgument(2);
            supplier.fetchBatches(batch -> {});
            os.write("id,name\n".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(csvStrategy).export(any(CustomerBatchSupplier.class), any(), any(OutputStream.class));

        when(csvStrategy.getContentType()).thenReturn("text/csv");
        when(csvStrategy.getFileExtension()).thenReturn(".csv");
        when(customerRepository.findAllByOrderByIdAsc(Limit.of(1000))).thenReturn(Collections.emptyList());

        ExportRequest request = new ExportRequest(FormatEnum.CSV, List.of("id", "name"));

        // When
        ExportResult result = exportService.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.contentType()).isEqualTo("text/csv");
        verify(appMetricsService).recordExport(eq("CSV"), eq(0), any(Duration.class));
        verify(entityManager, never()).clear();
    }

    @Test
    @DisplayName("Should integrate with real strategies and transform mock customer entities into actual CSV content")
    void testExportWithRealCsvStrategyAndMockData() {
        // Given real strategies wired with mock repository and metrics
        ExportService serviceWithRealStrategies = new ExportService(
                List.of(new CsvExportStrategy(), new TxtExportStrategy(), new XlsxExportStrategy()),
                customerRepository,
                appMetricsService,
                entityManager,
                1000
        );

        when(customerRepository.findAllByOrderByIdAsc(Limit.of(1000))).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.CSV, List.of("id", "name", "country"));

        // When
        ExportResult result = serviceWithRealStrategies.exportCustomers(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.contentType()).isEqualTo("text/csv; charset=UTF-8");

        String csvString = new String(result.data(), StandardCharsets.UTF_8);
        assertThat(csvString).contains("id,name,country");
        assertThat(csvString).contains("1,Alice Johnson,Portugal");
        assertThat(csvString).contains("2,Bob Smith,Spain");
        assertThat(csvString).contains("3,Carlos Silva,Brazil");

        verify(entityManager).clear();
        verify(appMetricsService).recordExport(eq("CSV"), eq(3), any(Duration.class));
    }

    @Test
    @DisplayName("Should integrate with real TXT strategy and format rows")
    void testExportWithRealTxtStrategyAndMockData() {
        ExportService serviceWithRealStrategies = new ExportService(
                List.of(new CsvExportStrategy(), new TxtExportStrategy(), new XlsxExportStrategy()),
                customerRepository,
                appMetricsService,
                entityManager,
                1000
        );

        when(customerRepository.findAllByOrderByIdAsc(Limit.of(1000))).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.TXT, List.of("name", "country"));

        ExportResult result = serviceWithRealStrategies.exportCustomers(request);

        assertThat(result).isNotNull();
        assertThat(result.contentType()).isEqualTo("text/plain; charset=UTF-8");
        String txt = new String(result.data(), StandardCharsets.UTF_8);
        assertThat(txt).contains("NAME");
        assertThat(txt).contains("COUNTRY");
        assertThat(txt).contains("Alice Johnson");
        assertThat(txt).contains("Portugal");
    }

    @Test
    @DisplayName("Should integrate with real XLSX strategy and generate valid workbook bytes")
    void testExportWithRealXlsxStrategyAndMockData() {
        ExportService serviceWithRealStrategies = new ExportService(
                List.of(new CsvExportStrategy(), new TxtExportStrategy(), new XlsxExportStrategy()),
                customerRepository,
                appMetricsService,
                entityManager,
                1000
        );

        when(customerRepository.findAllByOrderByIdAsc(Limit.of(1000))).thenReturn(mockCustomers);

        ExportRequest request = new ExportRequest(FormatEnum.XLSX, List.of("id", "name", "age"));

        ExportResult result = serviceWithRealStrategies.exportCustomers(request);

        assertThat(result).isNotNull();
        assertThat(result.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        // Verify ZIP / XLSX magic bytes (PK..)
        assertThat(result.data().length).isGreaterThan(100);
        assertThat(result.data()[0]).isEqualTo((byte) 0x50);
        assertThat(result.data()[1]).isEqualTo((byte) 0x4B);
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
                appMetricsService,
                entityManager
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
