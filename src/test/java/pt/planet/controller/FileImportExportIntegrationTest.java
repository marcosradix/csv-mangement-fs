package pt.planet.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pt.planet.observability.CorrelationIdFilter;
import pt.planet.repository.CustomerRepository;
import pt.planet.repository.ImportErrorRepository;
import pt.planet.repository.ImportRepository;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = pt.planet.Application.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileImportExportIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private CustomerRepository customerRepository;

        @Autowired
        private ImportRepository importRepository;

        @Autowired
        private ImportErrorRepository importErrorRepository;

        @BeforeEach
        void cleanUp() {
                importErrorRepository.deleteAll();
                importRepository.deleteAll();
                customerRepository.deleteAll();
        }

        @Test
        @DisplayName("Complete E2E: Import CSV, Query Customers, Export Multi-format, Check Errors and Observability")
        void testCompleteE2EWorkflow() throws Exception {
                // 1. Import customers_01.csv
                byte[] csv1 = Files.readAllBytes(Path.of("samples/customers_01.csv"));
                MockMultipartFile file1 = new MockMultipartFile("files", "customers_01.csv", "text/csv", csv1);

                MvcResult importResult = mockMvc.perform(multipart("/api/v1/imports")
                                .file(file1)
                                .header("X-Correlation-ID", "test-corr-123"))
                                .andExpect(status().isOk())
                                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, "test-corr-123"))
                                .andExpect(jsonPath("$[0].status", is("SUCCESS")))
                                .andExpect(jsonPath("$[0].totalRecords", is(3)))
                                .andExpect(jsonPath("$[0].successfulRecords", is(3)))
                                .andExpect(jsonPath("$[0].failedRecords", is(0)))
                                .andReturn();

                String responseJson = importResult.getResponse().getContentAsString();
                assertThat(responseJson).contains("importId");

                // 2. Query all customers with pagination
                mockMvc.perform(get("/api/v1/customers"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content", hasSize(3)))
                                .andExpect(jsonPath("$.content[0].name", is("John Smith")))
                                .andExpect(jsonPath("$.totalElements", is(3)))
                                .andExpect(jsonPath("$.totalPages", is(1)))
                                .andExpect(jsonPath("$.page", is(0)))
                                .andExpect(jsonPath("$.size", is(20)));

                // 3. Import customers_03.csv (Partial Success)
                byte[] csv3 = Files.readAllBytes(Path.of("samples/customers_03.csv"));
                MockMultipartFile file3 = new MockMultipartFile("files", "customers_03.csv", "text/csv", csv3);

                MvcResult partialResult = mockMvc.perform(multipart("/api/v1/imports").file(file3))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].status", is("PARTIAL_SUCCESS")))
                                .andExpect(jsonPath("$[0].totalRecords", is(2)))
                                .andExpect(jsonPath("$[0].successfulRecords", is(1)))
                                .andExpect(jsonPath("$[0].failedRecords", is(1)))
                                .andReturn();

                String import3Json = partialResult.getResponse().getContentAsString();
                String import3Id = import3Json.split("\"importId\":\"")[1].split("\"")[0];

                // 4. Query Import Errors for import3 (grouped by row)
                mockMvc.perform(get("/api/v1/imports/" + import3Id + "/errors"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(1))) // age and email errors grouped for row 3
                                .andExpect(jsonPath("$[0].filename", is("customers_03.csv")))
                                .andExpect(jsonPath("$[0].rowNumber", is(3)))
                                .andExpect(jsonPath("$[0].fieldNames", hasItems("email", "age")))
                                .andExpect(jsonPath("$[0].errorMessages", hasSize(2)))
                                .andExpect(jsonPath("$[0].rawData", containsString("Marco Rossi")));

                // 5. Dynamic Export: CSV format with selected columns
                String csvExportPayload = """
                                {
                                    "format": "CSV",
                                    "columns": ["id", "name", "country"]
                                }
                                """;
                mockMvc.perform(post("/api/v1/exports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(csvExportPayload))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Content-Disposition", containsString("customers_")))
                                .andExpect(content().string(containsString("id,name,country")))
                                .andExpect(content().string(containsString("1,John Smith,Portugal")));

                // 6. Dynamic Export: TXT format with selected columns
                String txtExportPayload = """
                                {
                                    "format": "TXT",
                                    "columns": ["name", "email"]
                                }
                                """;
                mockMvc.perform(post("/api/v1/exports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(txtExportPayload))
                                .andExpect(status().isOk())
                                .andExpect(content().string(containsString("NAME")))
                                .andExpect(content().string(containsString("John Smith")));

                // 7. Dynamic Export: XLSX format
                String xlsxExportPayload = """
                                {
                                    "format": "XLSX",
                                    "columns": ["id", "name", "phone"]
                                }
                                """;
                mockMvc.perform(post("/api/v1/exports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(xlsxExportPayload))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Content-Type", is(
                                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")));

                // 8. Invalid Column in Export -> RFC 9457 Problem Details (HTTP 400)
                String invalidExportPayload = """
                                {
                                    "format": "CSV",
                                    "columns": ["id", "non_existent_column"]
                                }
                                """;
                mockMvc.perform(post("/api/v1/exports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidExportPayload))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status", is(400)))
                                .andExpect(jsonPath("$.title", is("Invalid Column")))
                                .andExpect(jsonPath("$.detail",
                                                containsString("Unsupported export column: 'non_existent_column'")))
                                .andExpect(jsonPath("$.type", is("https://planet.pt/problems/invalid-column")));

                // 9. Observability: Actuator Health & Probes
                mockMvc.perform(get("/actuator/health"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status", is("UP")))
                                .andExpect(jsonPath("$.components.application.status", is("UP")))
                                .andExpect(jsonPath("$.components.application.details.status", is("OPERATIONAL")));

                mockMvc.perform(get("/actuator/health/liveness"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status", is("UP")));

                mockMvc.perform(get("/actuator/health/readiness"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status", is("UP")));

                // 10. Observability: Prometheus Metrics
                mockMvc.perform(get("/actuator/prometheus"))
                                .andExpect(status().isOk())
                                .andExpect(content().string(containsString("file_import_count")))
                                .andExpect(content().string(containsString("file_export_count")));
        }

        @Test
        @DisplayName("Should receive multiple CSV files with related data in a single request, merge them, and support pagination")
        void testMultiFileImportWithRelatedDataAndPagination() throws Exception {
                // Upload customers_01.csv and customers_02.csv together in a single request
                byte[] csv1 = Files.readAllBytes(Path.of("samples/customers_01.csv"));
                byte[] csv2 = Files.readAllBytes(Path.of("samples/customers_02.csv"));

                MockMultipartFile file1 = new MockMultipartFile("files", "customers_01.csv", "text/csv", csv1);
                MockMultipartFile file2 = new MockMultipartFile("files", "customers_02.csv", "text/csv", csv2);

                mockMvc.perform(multipart("/api/v1/imports")
                                .file(file1)
                                .file(file2))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(2)))
                                .andExpect(jsonPath("$[0].filename", is("customers_01.csv")))
                                .andExpect(jsonPath("$[0].status", is("SUCCESS")))
                                .andExpect(jsonPath("$[0].totalRecords", is(3)))
                                .andExpect(jsonPath("$[1].filename", is("customers_02.csv")))
                                .andExpect(jsonPath("$[1].status", is("PARTIAL_SUCCESS")))
                                .andExpect(jsonPath("$[1].totalRecords", is(2)))
                                .andExpect(jsonPath("$[1].successfulRecords", is(1)))
                                .andExpect(jsonPath("$[1].failedRecords", is(1)));

                // Test Pagination: Page 0 with size 2, sorted by ID ascending
                mockMvc.perform(get("/api/v1/customers")
                                .param("page", "0")
                                .param("size", "2")
                                .param("sortBy", "id")
                                .param("direction", "ASC"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content", hasSize(2)))
                                .andExpect(jsonPath("$.totalElements", is(3)))
                                .andExpect(jsonPath("$.totalPages", is(2)))
                                .andExpect(jsonPath("$.page", is(0)))
                                .andExpect(jsonPath("$.size", is(2)))
                                .andExpect(jsonPath("$.isFirst", is(true)))
                                .andExpect(jsonPath("$.isLast", is(false)))
                                .andExpect(jsonPath("$.content[0].id", is(1)))
                                .andExpect(jsonPath("$.content[0].name", is("John Smith")))
                                // Customer 1 related data merged: phone came from customers_02.csv!
                                .andExpect(jsonPath("$.content[0].phone", is("+351910000000")))
                                .andExpect(jsonPath("$.content[1].id", is(2)));

                // Test Pagination: Page 1 with size 2
                mockMvc.perform(get("/api/v1/customers")
                                .param("page", "1")
                                .param("size", "2")
                                .param("sortBy", "id")
                                .param("direction", "ASC"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content", hasSize(1)))
                                .andExpect(jsonPath("$.page", is(1)))
                                .andExpect(jsonPath("$.isFirst", is(false)))
                                .andExpect(jsonPath("$.isLast", is(true)))
                                .andExpect(jsonPath("$.content[0].id", is(3)))
                                .andExpect(jsonPath("$.content[0].name", is("Bob Smith")));

                // Test Sorting by Name descending
                mockMvc.perform(get("/api/v1/customers")
                                .param("page", "0")
                                .param("size", "1")
                                .param("sortBy", "name")
                                .param("direction", "DESC"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content", hasSize(1)))
                                .andExpect(jsonPath("$.content[0].name", is("John Smith")));
        }

        @Test
        @DisplayName("Batch import with invalid file should not fail whole batch and process valid files")
        void testBatchImportWithInvalidFileContinuesProcessingValidFiles() throws Exception {
                byte[] validCsv = Files.readAllBytes(Path.of("samples/customers_01.csv"));
                MockMultipartFile validFile = new MockMultipartFile("files", "customers_01.csv", "text/csv", validCsv);
                MockMultipartFile emptyFile = new MockMultipartFile("files", "empty.csv", "text/csv", new byte[0]);

                mockMvc.perform(multipart("/api/v1/imports")
                                .file(validFile)
                                .file(emptyFile))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", hasSize(2)))
                                .andExpect(jsonPath("$[0].filename", is("customers_01.csv")))
                                .andExpect(jsonPath("$[0].status", is("SUCCESS")))
                                .andExpect(jsonPath("$[0].totalRecords", is(3)))
                                .andExpect(jsonPath("$[1].filename", is("empty.csv")))
                                .andExpect(jsonPath("$[1].status", is("FAILED")))
                                .andExpect(jsonPath("$[1].totalRecords", is(0)));

                // Verify valid records were saved into DB
                assertThat(customerRepository.count()).isEqualTo(3);
        }

        @Test
        @DisplayName("Unsupported export format should return HTTP 400 Bad Request with RFC 9457 Problem Detail")
        void testUnsupportedExportFormatReturns400() throws Exception {
                String invalidFormatPayload = """
                                {
                                    "format": "TXT0",
                                    "columns": ["id", "name"]
                                }
                                """;
                mockMvc.perform(post("/api/v1/exports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidFormatPayload))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status", is(400)))
                                .andExpect(jsonPath("$.title", is("Invalid Export Format")))
                                .andExpect(jsonPath("$.detail", containsString("Unsupported export format: 'TXT0'")))
                                .andExpect(jsonPath("$.type", is("https://planet.pt/problems/invalid-export-format")));
        }

        @Test
        @DisplayName("Malformed JSON payload should return HTTP 400 Bad Request with Problem Detail")
        void testMalformedJsonPayloadReturns400() throws Exception {
                mockMvc.perform(post("/api/v1/exports")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ invalid json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.status", is(400)))
                                .andExpect(jsonPath("$.title", is("Malformed Request")))
                                .andExpect(jsonPath("$.type", is("https://planet.pt/problems/malformed-request")));
        }
}
