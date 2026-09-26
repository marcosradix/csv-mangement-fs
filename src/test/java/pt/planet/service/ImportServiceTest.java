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

import pt.planet.importfile.CustomerRecord;

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

    @Test
    @DisplayName("Should continue processing valid CSV files when batch contains invalid files (empty and no data rows)")
    void testBatchImportWithInvalidFilesContinuesAndProcessesValidFiles() throws Exception {
        byte[] valid1 = Files.readAllBytes(Path.of("samples/customers_01.csv"));
        byte[] valid2 = Files.readAllBytes(Path.of("samples/customers_02.csv"));

        MockMultipartFile file1 = new MockMultipartFile("files", "customers_01.csv", "text/csv", valid1);
        MockMultipartFile file2Empty = new MockMultipartFile("files", "empty.csv", "text/csv", new byte[0]);
        MockMultipartFile file3NoRows = new MockMultipartFile("files", "no_rows.csv", "text/csv",
                "id,name,email,age,country,phone\n".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file4 = new MockMultipartFile("files", "customers_02.csv", "text/csv", valid2);

        List<ImportResponse> responses = importService.processImports(List.of(file1, file2Empty, file3NoRows, file4));

        assertThat(responses).hasSize(4);

        // File 1: Success
        assertThat(responses.get(0).getFilename()).isEqualTo("customers_01.csv");
        assertThat(responses.get(0).getStatus()).isEqualTo(ImportResponse.StatusEnum.SUCCESS);
        assertThat(responses.get(0).getTotalRecords()).isEqualTo(3);

        // File 2: Empty -> Failed
        assertThat(responses.get(1).getFilename()).isEqualTo("empty.csv");
        assertThat(responses.get(1).getStatus()).isEqualTo(ImportResponse.StatusEnum.FAILED);
        assertThat(responses.get(1).getTotalRecords()).isEqualTo(0);

        // File 3: No rows -> Failed
        assertThat(responses.get(2).getFilename()).isEqualTo("no_rows.csv");
        assertThat(responses.get(2).getStatus()).isEqualTo(ImportResponse.StatusEnum.FAILED);
        assertThat(responses.get(2).getTotalRecords()).isEqualTo(0);

        // File 4: Valid with 1 success, 1 failure -> Partial Success
        assertThat(responses.get(3).getFilename()).isEqualTo("customers_02.csv");
        assertThat(responses.get(3).getStatus()).isEqualTo(ImportResponse.StatusEnum.PARTIAL_SUCCESS);

        // Verify valid customers from files 1 and 4 were persisted and merged
        assertThat(customerRepository.findById(1L)).isPresent();
        assertThat(customerRepository.findById(1L).get().getPhone()).isEqualTo("+351910000000");
        assertThat(customerRepository.findById(2L)).isPresent();
        assertThat(customerRepository.findById(3L)).isPresent();

        // Verify errors can be queried for the failed empty file
        List<GroupedImportErrorResponse> emptyErrors = importService.getImportErrors(responses.get(1).getImportId());
        assertThat(emptyErrors).isNotEmpty();
        assertThat(emptyErrors.get(0).getFilename()).isEqualTo("empty.csv");
        assertThat(emptyErrors.get(0).getErrorMessages().get(0)).contains("empty");

        // Verify errors can be queried for the no-rows file
        List<GroupedImportErrorResponse> noRowsErrors = importService.getImportErrors(responses.get(2).getImportId());
        assertThat(noRowsErrors).isNotEmpty();
        assertThat(noRowsErrors.get(0).getFilename()).isEqualTo("no_rows.csv");
        assertThat(noRowsErrors.get(0).getErrorMessages().get(0)).contains("no data records");
    }

    @Test
    @DisplayName("Should skip insert/update and preserve version and timestamps when re-importing identical records")
    void testSkipInsertOrUpdateWhenAllPresentFieldsEqual() throws Exception {
        // Initial import of 3 records
        byte[] content = Files.readAllBytes(Path.of("samples/customers_01.csv"));
        MockMultipartFile file1 = new MockMultipartFile("file", "customers_01.csv", "text/csv", content);
        ImportResponse response1 = importService.processImport(file1);

        assertThat(response1.getStatus()).isEqualTo(ImportResponse.StatusEnum.SUCCESS);
        assertThat(response1.getSuccessfulRecords()).isEqualTo(3);

        List<CustomerEntity> initialCustomers = customerRepository.findAllByOrderByIdAsc();
        assertThat(initialCustomers).hasSize(3);
        CustomerEntity c1Before = initialCustomers.get(0);
        assertThat(c1Before.getVersion()).isEqualTo(0L);
        var c1UpdatedAtBefore = c1Before.getUpdatedAt();

        // Re-import identical file: all fields present in CSV are equal to DB
        MockMultipartFile file2 = new MockMultipartFile("file", "customers_01.csv", "text/csv", content);
        ImportResponse response2 = importService.processImport(file2);

        // Status is SUCCESS, counted as successful, failedRecords is 0
        assertThat(response2.getStatus()).isEqualTo(ImportResponse.StatusEnum.SUCCESS);
        assertThat(response2.getTotalRecords()).isEqualTo(3);
        assertThat(response2.getSuccessfulRecords()).isEqualTo(3);
        assertThat(response2.getFailedRecords()).isEqualTo(0);

        // Verify version and updatedAt were NOT modified (no UPDATE statement executed)
        List<CustomerEntity> afterCustomers = customerRepository.findAllByOrderByIdAsc();
        assertThat(afterCustomers).hasSize(3);
        for (int i = 0; i < 3; i++) {
            CustomerEntity before = initialCustomers.get(i);
            CustomerEntity after = afterCustomers.get(i);
            assertThat(after.getVersion()).isEqualTo(0L);
            assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
            assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        }
    }

    @Test
    @DisplayName("Should skip update when only a subset of fields is present in CSV and all match the DB")
    void testPartialFieldsEqualSkipsUpdateAndPreservesUnmappedFields() throws Exception {
        // First import batch 1 and 2 so customer 1 has a phone number (+351910000000)
        byte[] content1 = Files.readAllBytes(Path.of("samples/customers_01.csv"));
        importService.processImport(new MockMultipartFile("file", "customers_01.csv", "text/csv", content1));

        byte[] content2 = Files.readAllBytes(Path.of("samples/customers_02.csv"));
        importService.processImport(new MockMultipartFile("file", "customers_02.csv", "text/csv", content2));

        CustomerEntity c1WithPhone = customerRepository.findById(1L).orElseThrow();
        assertThat(c1WithPhone.getPhone()).isEqualTo("+351910000000");
        Long versionBefore = c1WithPhone.getVersion();
        var updatedAtBefore = c1WithPhone.getUpdatedAt();

        // Now import a CSV that does not include phone (customers_01.csv)
        // Fields present in CSV are: id, name, email, age, country - all equal to DB
        ImportResponse response = importService.processImport(new MockMultipartFile("file", "customers_01.csv", "text/csv", content1));

        assertThat(response.getStatus()).isEqualTo(ImportResponse.StatusEnum.SUCCESS);

        CustomerEntity c1After = customerRepository.findById(1L).orElseThrow();
        // Phone must still be preserved
        assertThat(c1After.getPhone()).isEqualTo("+351910000000");
        // Version and updatedAt must NOT have changed
        assertThat(c1After.getVersion()).isEqualTo(versionBefore);
        assertThat(c1After.getUpdatedAt()).isEqualTo(updatedAtBefore);
    }

    @Test
    @DisplayName("Should perform update and increment version when at least one present field differs")
    void testDifferentFieldTriggersUpdate() throws Exception {
        // Initial import
        byte[] content1 = Files.readAllBytes(Path.of("samples/customers_01.csv"));
        importService.processImport(new MockMultipartFile("file", "customers_01.csv", "text/csv", content1));

        CustomerEntity c1Initial = customerRepository.findById(1L).orElseThrow();
        assertThat(c1Initial.getVersion()).isEqualTo(0L);

        // Import CSV with changed country for customer 1
        String updateCsv = "id,name,email,age,country\n1,John Smith,john@example.com,35,Spain\n";
        ImportResponse response = importService.processImport(
                new MockMultipartFile("file", "update_country.csv", "text/csv", updateCsv.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(ImportResponse.StatusEnum.SUCCESS);
        assertThat(response.getSuccessfulRecords()).isEqualTo(1);

        CustomerEntity c1Updated = customerRepository.findById(1L).orElseThrow();
        assertThat(c1Updated.getCountry()).isEqualTo("Spain");
        assertThat(c1Updated.getVersion()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Should return correct UpsertResult enum from upsertCustomer")
    void testDirectUpsertCustomerResultEnum() {
        CustomerRecord newRecord = new CustomerRecord(999L, "Alice Wonder", "alice@example.com", 25, "UK", null, 2, "999,Alice Wonder,alice@example.com,25,UK");
        ImportService.UpsertResult r1 = importService.upsertCustomer(newRecord);
        assertThat(r1).isEqualTo(ImportService.UpsertResult.INSERTED);

        // Call again with identical data -> SKIPPED_IDENTICAL
        ImportService.UpsertResult r2 = importService.upsertCustomer(newRecord);
        assertThat(r2).isEqualTo(ImportService.UpsertResult.SKIPPED_IDENTICAL);

        // Call with updated age -> UPDATED
        CustomerRecord updatedRecord = new CustomerRecord(999L, "Alice Wonder", "alice@example.com", 26, "UK", null, 2, "999,Alice Wonder,alice@example.com,26,UK");
        ImportService.UpsertResult r3 = importService.upsertCustomer(updatedRecord);
        assertThat(r3).isEqualTo(ImportService.UpsertResult.UPDATED);
    }
}
