package pt.planet.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import pt.planet.Application;
import pt.planet.domain.CustomerEntity;
import pt.planet.domain.ImportErrorEntity;
import pt.planet.dto.GroupedImportErrorResponse;
import pt.planet.dto.ImportResponse;
import pt.planet.repository.CustomerRepository;
import pt.planet.repository.ImportErrorRepository;
import pt.planet.repository.ImportRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class ImportServiceTest {

    @Autowired
    private ImportService importService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ImportRepository importRepository;

    @Autowired
    private ImportErrorRepository importErrorRepository;

    @Autowired
    private pt.planet.mapper.CustomerMapper customerMapper;

    @BeforeEach
    void cleanUp() {
        importErrorRepository.deleteAll();
        importRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    @DisplayName("Should successfully import customers_01.csv with 3 valid records")
    void testImportCustomers01() throws Exception {
        byte[] content = Files.readAllBytes(Path.of("samples/customers_01.csv"));
        MockMultipartFile file = new MockMultipartFile("file", "customers_01.csv", "text/csv", content);

        ImportResponse response = importService.processImport(file);

        assertThat(response.getStatus()).isEqualTo(ImportResponse.StatusEnum.SUCCESS);
        assertThat(response.getTotalRecords()).isEqualTo(3);
        assertThat(response.getSuccessfulRecords()).isEqualTo(3);
        assertThat(response.getFailedRecords()).isEqualTo(0);

        List<CustomerEntity> customers = customerRepository.findAllByOrderByIdAsc();
        assertThat(customers).hasSize(3);
        assertThat(customers.get(0).getName()).isEqualTo("John Smith");
        assertThat(customers.get(0).getCountry()).isEqualTo("Portugal");
        assertThat(customers.get(0).getPhone()).isNull();
    }

    @Test
    @DisplayName("Should import customers_02.csv with PARTIAL_SUCCESS: update customer 1 and store error for customer 4 without stopping")
    void testImportCustomers02Upsert() throws Exception {
        // First import batch 1
        byte[] content1 = Files.readAllBytes(Path.of("samples/customers_01.csv"));
        importService.processImport(new MockMultipartFile("file", "customers_01.csv", "text/csv", content1));

        // Now import batch 2 (contains customer 1 with age 35, and customer 4 with empty age)
        byte[] content2 = Files.readAllBytes(Path.of("samples/customers_02.csv"));
        ImportResponse response = importService
                .processImport(new MockMultipartFile("file", "customers_02.csv", "text/csv", content2));

        // Import completes without stopping: status is PARTIAL_SUCCESS
        assertThat(response.getStatus()).isEqualTo(ImportResponse.StatusEnum.PARTIAL_SUCCESS);
        assertThat(response.getTotalRecords()).isEqualTo(2);
        assertThat(response.getSuccessfulRecords()).isEqualTo(1);
        assertThat(response.getFailedRecords()).isEqualTo(1);

        // Verify customer 1 was updated with phone number
        Optional<CustomerEntity> customer1 = customerRepository.findById(1L);
        assertThat(customer1).isPresent();
        assertThat(customer1.get().getName()).isEqualTo("John Smith");
        assertThat(customer1.get().getPhone()).isEqualTo("+351910000000");

        // Verify customer 4 was NOT inserted because age is empty
        Optional<CustomerEntity> customer4 = customerRepository.findById(4L);
        assertThat(customer4).isEmpty();

        // Verify error was persisted in import_errors table just like email errors
        List<ImportErrorEntity> errors = importErrorRepository
                .findByImportIdOrderByRowNumberAsc(response.getImportId());
        assertThat(errors).isNotEmpty();
        assertThat(errors).allMatch(e -> "customers_02.csv".equals(e.getFilename()));
        assertThat(errors).anyMatch(e -> e.getRowNumber() == 3 && e.getFieldName().equals("age")
                && e.getErrorMessage().contains("cannot be null or empty"));

        // Total customers in database remains 3 (1, 2, 3)
        assertThat(customerRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should reject update and store error without stopping when incoming age is empty")
    void testRejectUpdateWhenAgeIsEmpty() throws Exception {
        // Initial insert of customer 1
        byte[] content1 = Files.readAllBytes(Path.of("samples/customers_01.csv"));
        importService.processImport(new MockMultipartFile("file", "customers_01.csv", "text/csv", content1));

        CustomerEntity originalCustomer1 = customerRepository.findById(1L).orElseThrow();
        assertThat(originalCustomer1.getAge()).isEqualTo(35);

        // Attempt to update customer 1 with empty age
        String updateCsv = "id,name,email,age,country,phone\n1,John Smith Updated,john@example.com,,Portugal,+351999999999\n";
        ImportResponse response = importService.processImport(
                new MockMultipartFile("file", "update_empty_age.csv", "text/csv", updateCsv.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(ImportResponse.StatusEnum.FAILED);
        assertThat(response.getSuccessfulRecords()).isEqualTo(0);
        assertThat(response.getFailedRecords()).isEqualTo(1);

        // Customer 1 was NOT updated
        CustomerEntity unchangedCustomer1 = customerRepository.findById(1L).orElseThrow();
        assertThat(unchangedCustomer1.getName()).isEqualTo("John Smith");
        assertThat(unchangedCustomer1.getAge()).isEqualTo(35);
        assertThat(unchangedCustomer1.getPhone()).isNull();

        // Error is stored in import_errors with correct filename
        List<ImportErrorEntity> errors = importErrorRepository
                .findByImportIdOrderByRowNumberAsc(response.getImportId());
        assertThat(errors).allMatch(e -> "update_empty_age.csv".equals(e.getFilename()));
        assertThat(errors).anyMatch(e -> e.getFieldName().equals("age") && e.getErrorMessage().contains("cannot be null or empty"));
    }

    @Test
    @DisplayName("Should import customers_03.csv with PARTIAL_SUCCESS and persist errors for invalid record 5")
    void testImportCustomers03PartialSuccess() throws Exception {
        byte[] content = Files.readAllBytes(Path.of("samples/customers_03.csv"));
        MockMultipartFile file = new MockMultipartFile("file", "customers_03.csv", "text/csv", content);

        ImportResponse response = importService.processImport(file);

        assertThat(response.getStatus()).isEqualTo(ImportResponse.StatusEnum.PARTIAL_SUCCESS);
        assertThat(response.getTotalRecords()).isEqualTo(2);
        assertThat(response.getSuccessfulRecords()).isEqualTo(1);
        assertThat(response.getFailedRecords()).isEqualTo(1);

        // Verify customer 1 is persisted
        Optional<CustomerEntity> customer1 = customerRepository.findById(1L);
        assertThat(customer1).isPresent();
        assertThat(customer1.get().getName()).isEqualTo("John Smith");

        // Verify customer 5 is NOT persisted
        Optional<CustomerEntity> customer5 = customerRepository.findById(5L);
        assertThat(customer5).isEmpty();

        // Verify error is recorded in import_errors table with correct filename
        List<ImportErrorEntity> errors = importErrorRepository
                .findByImportIdOrderByRowNumberAsc(response.getImportId());
        assertThat(errors).isNotEmpty();
        assertThat(errors).allMatch(e -> "customers_03.csv".equals(e.getFilename()));
        assertThat(errors).anyMatch(
                e -> e.getRowNumber() == 3 && (e.getFieldName().equals("email") || e.getFieldName().equals("age")));

        // Verify getImportErrors groups multiple errors for the same row
        List<GroupedImportErrorResponse> groupedErrors = importService.getImportErrors(response.getImportId());
        assertThat(groupedErrors).hasSize(1);
        GroupedImportErrorResponse grouped = groupedErrors.get(0);
        assertThat(grouped.getImportId()).isEqualTo(response.getImportId());
        assertThat(grouped.getFilename()).isEqualTo("customers_03.csv");
        assertThat(grouped.getRowNumber()).isEqualTo(3);
        assertThat(grouped.getFieldNames()).containsExactlyInAnyOrder("email", "age");
        assertThat(grouped.getErrorMessages()).hasSize(2);
        assertThat(grouped.getRawData()).contains("Marco Rossi");
    }

    @Test
    @DisplayName("Should process multiple CSV files containing related data and merge customer records")
    void testImportMultipleFilesRelatedDataMerging() throws Exception {
        byte[] content1 = Files.readAllBytes(Path.of("samples/customers_01.csv"));
        byte[] content2 = Files.readAllBytes(Path.of("samples/customers_02.csv"));

        MockMultipartFile file1 = new MockMultipartFile("files", "customers_01.csv", "text/csv", content1);
        MockMultipartFile file2 = new MockMultipartFile("files", "customers_02.csv", "text/csv", content2);

        List<ImportResponse> responses = importService.processImports(List.of(file1, file2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getStatus()).isEqualTo(ImportResponse.StatusEnum.SUCCESS);
        assertThat(responses.get(0).getTotalRecords()).isEqualTo(3);
        assertThat(responses.get(1).getStatus()).isEqualTo(ImportResponse.StatusEnum.PARTIAL_SUCCESS);
        assertThat(responses.get(1).getTotalRecords()).isEqualTo(2);
        assertThat(responses.get(1).getSuccessfulRecords()).isEqualTo(1);
        assertThat(responses.get(1).getFailedRecords()).isEqualTo(1);

        // Verify total customers created (1, 2, 3; customer 4 rejected due to empty age)
        assertThat(customerRepository.count()).isEqualTo(3);

        // Verify customer 1 merged data from both files
        CustomerEntity customer1 = customerRepository.findById(1L).orElseThrow();
        assertThat(customer1.getName()).isEqualTo("John Smith");
        assertThat(customer1.getEmail()).isEqualTo("john@example.com");
        assertThat(customer1.getAge()).isEqualTo(35);
        assertThat(customer1.getCountry()).isEqualTo("Portugal");
        assertThat(customer1.getPhone()).isEqualTo("+351910000000"); // Added by second file

        // Verify customer 4 was not created
        assertThat(customerRepository.findById(4L)).isEmpty();
    }

    @Test
    @DisplayName("Should correctly group errors by filename and rowNumber while keeping rawData as-is")
    void testGroupedErrorResponseStructure() {
        java.util.UUID importId = java.util.UUID.randomUUID();
        ImportErrorEntity err1 = new ImportErrorEntity(
                importId, "customers_04.csv", 3, "email", "Field 'email' has invalid format: 'marco@example'",
                "5,Marco Rossi,+39000000000,marco@example,thirty,Italy,test");
        ImportErrorEntity err2 = new ImportErrorEntity(
                importId, "customers_04.csv", 3, "age", "Field 'age' must be a valid integer, got: 'thirty'",
                "5,Marco Rossi,+39000000000,marco@example,thirty,Italy,test");
        ImportErrorEntity err3 = new ImportErrorEntity(
                importId, "customers_04.csv", 5, "phone", "Field 'phone' is invalid",
                "6,Lucia,null");

        List<GroupedImportErrorResponse> result = customerMapper.toGroupedErrorResponseList(List.of(err1, err2, err3));

        assertThat(result).hasSize(2);

        // First group (row 3)
        GroupedImportErrorResponse row3 = result.get(0);
        assertThat(row3.getImportId()).isEqualTo(importId);
        assertThat(row3.getFilename()).isEqualTo("customers_04.csv");
        assertThat(row3.getRowNumber()).isEqualTo(3);
        assertThat(row3.getFieldNames()).containsExactly("email", "age");
        assertThat(row3.getErrorMessages()).containsExactly(
                "Field 'email' has invalid format: 'marco@example'",
                "Field 'age' must be a valid integer, got: 'thirty'");
        assertThat(row3.getRawData()).isEqualTo("5,Marco Rossi,+39000000000,marco@example,thirty,Italy,test");

        // Second group (row 5)
        GroupedImportErrorResponse row5 = result.get(1);
        assertThat(row5.getImportId()).isEqualTo(importId);
        assertThat(row5.getFilename()).isEqualTo("customers_04.csv");
        assertThat(row5.getRowNumber()).isEqualTo(5);
        assertThat(row5.getFieldNames()).containsExactly("phone");
        assertThat(row5.getErrorMessages()).containsExactly("Field 'phone' is invalid");
        assertThat(row5.getRawData()).isEqualTo("6,Lucia,null");
    }
}
