# CSV Management & File Import/Export Service (`csv-management-fs`)

A production-grade, API-First backend service built natively with **Java 25** and **Spring Boot 3.5.16**. It provides dynamic CSV parsing, multi-file batch imports with data merging, pageable customer queries, dynamic multi-format exports (CSV, TXT, Excel XLSX), robust concurrency control via JPA optimistic locking, and a full observability suite (Micrometer & Prometheus).

---

## Table of Contents
- [Architecture & Design](#architecture--design)
- [Key Features](#key-features)
- [Tech Stack & Dependencies](#tech-stack--dependencies)
- [API Reference](#api-reference)
  - [1. Import Operations](#1-import-operations)
  - [2. Customer Operations](#2-customer-operations)
  - [3. Export Operations](#3-export-operations)
  - [4. Observability & Health](#4-observability--health)
- [How to Use Observability](#how-to-use-observability)
  - [1. Tracing with Correlation IDs](#1-tracing-with-correlation-ids)
  - [2. Health Probes & Business Status](#2-health-probes--business-status)
  - [3. Querying Prometheus & Actuator Metrics](#3-querying-prometheus--actuator-metrics)
- [Import Error Logging](#import-error-logging)
- [Error Handling (RFC 9457)](#error-handling-rfc-9457)
- [Running the Application](#running-the-application)
  - [Option A: Docker Compose (Recommended)](#option-a-docker-compose-recommended)
  - [Option B: Local Maven Execution](#option-b-local-maven-execution)
- [Testing & Quality Assurance](#testing--quality-assurance)

---

## Architecture & Design

The service follows an **API-First / Contract-First** design pattern, generating interface stubs and DTOs from an OpenAPI 3.0 specification (`src/main/resources/openapi/api-spec.yaml`).

```
                  ┌──────────────────────────────────────────────┐
                  │          HTTP REST Clients / Swagger UI       │
                  └──────────────────────┬───────────────────────┘
                                         │
                 ┌───────────────────────▼────────────────────────┐
                 │     CorrelationIdFilter (X-Correlation-ID)     │
                 └───────────────────────┬────────────────────────┘
                                         │
                 ┌───────────────────────▼────────────────────────┐
                 │      Generated OpenAPI Controllers & Models    │
                 │      (pt.planet.api, pt.planet.controller)     │
                 └───────┬────────────────────────────────┬───────┘
                         │                                │
            ┌────────────▼─────────────┐     ┌────────────▼────────────┐
            │      ImportService       │     │      ExportService      │
            └────────────┬─────────────┘     └────────────┬────────────┘
                         │                                │
         ┌───────────────┼───────────────┐                │
         ▼               ▼               ▼                ▼
┌─────────────────┐ ┌─────────┐ ┌─────────────────┐ ┌───────────────────┐
│CsvHeaderAnalyzer│ │CsvParser│ │CustomerValidator│ │  ExportStrategy   │
└─────────────────┘ └─────────┘ └─────────────────┘ │ (CSV, TXT, XLSX)  │
         │               │               │          └───────────────────┘
         └───────────────┼───────────────┘
                         │
                 ┌───────▼───────────────────────────────┐
                 │  Spring Data JPA + PostgreSQL/Flyway  │
                 │    (Optimistic Locking via @Version)  │
                 └───────┬───────────────────────────────┘
                         │
        ┌────────────────▼────────────────┐
        │  Actuator + Micrometer Metrics  │
        │  (Health, Liveness, Prometheus) │
        └─────────────────────────────────┘
```

---

## Key Features

1. **Dynamic CSV Import Pipeline**:
   - **Header Flexibility**: Scans and detects headers regardless of column order, case sensitivity, or aliases (e.g. `telephone` -> `phone`). Tolerates extraneous unknown columns without failure.
   - **Row-Level Error Isolation**: Invalid rows are caught, persisted in `import_errors`, and logged without aborting the entire batch.
   - **Idempotent Upsert Logic**: Merges incoming customer updates by ID. If a customer already exists, non-empty fields update the record; otherwise, a new record is created.
   - **Multi-File Batch Imports**: Accepts multiple CSV files in a single request, merging related records across files (e.g. basic customer data in file 1 and contact information in file 2).
   - **Status Tracking**: Import batches are tracked with statuses: `SUCCESS`, `PARTIAL_SUCCESS`, or `FAILED`.

2. **Pageable Customer Queries**:
   - `GET /api/v1/customers` supports `page`, `size`, `sortBy`, and `direction` (`ASC`/`DESC`).
   - Secure field validation prevents JPA attribute injection.

3. **Pluggable Dynamic Export Engine**:
   - Implements the **Strategy Pattern** across multiple output formats:
     - **CSV**: Standard comma-separated format via Apache Commons CSV.
     - **TXT**: Formatted fixed-width tabular report with dynamically calculated column widths.
     - **XLSX**: Styled Microsoft Excel spreadsheet with headers and auto-sized columns via Apache POI.
   - Allows clients to request any subset of columns in any desired sequence.

4. **Data Integrity & Concurrency**:
   - Optimistic locking via JPA `@Version` on `CustomerEntity` guarantees detection of concurrent write collisions.

5. **Observability & Health**:
   - Request tracking via `X-Correlation-ID` header and SLF4J MDC.
   - Custom Micrometer counters and timers for import counts, processed record metrics, and export durations.
   - Custom Actuator health contributor showing live database entity counts at `/actuator/health`.
   - Prometheus scrape endpoint at `/actuator/prometheus`.

6. **Modern Tooling & Compatibility**:
   - Fully compatible with **Java 25**, **MapStruct 1.6.3**, **Project Lombok 1.18.42**, and **JSpecify 1.0.1** standard nullness annotations (`@NullMarked`, `@Nullable`).

---

## Tech Stack & Dependencies

| Technology | Version / Description |
| :--- | :--- |
| **Java** | 25 (Temurin 25.0.2 / Class file format 69) |
| **Spring Boot** | 3.5.16 (Web, Data JPA, Validation, Actuator) |
| **Nullness Specification** | JSpecify 1.0.1 (`@NullMarked`, `@Nullable`) |
| **Database** | PostgreSQL 16 (H2 in-memory for testing) |
| **Schema Migrations** | Flyway 11.x |
| **Code Generation** | OpenAPI Generator Maven Plugin 7.12.0 |
| **DTO Mapping** | MapStruct 1.6.3 + Lombok MapStruct Binding 0.2.0 |
| **Boilerplate Reduction**| Project Lombok 1.18.42 |
| **File Processing** | Apache Commons CSV 1.12.0, Apache POI 5.4.0 (OOXML) |
| **Metrics** | Micrometer Prometheus Registry |
| **Containerization** | Docker multi-stage build & Docker Compose |

---

## API Reference

Interactive API documentation is available via **Swagger UI** at:
`http://localhost:8081/swagger-ui.html`

### 1. Import Operations

#### `POST /api/v1/imports`
Uploads one or more CSV files for parsing, validation, and upsertion.

- **Request**: `multipart/form-data`
  - `files`: One or more CSV files (or `file` for single file backwards compatibility)
- **Response** (`200 OK`): Array of import execution results:
  ```json
  [
    {
      "importId": "9b3889ed-fda6-42cf-ba52-375e6e2775a0",
      "filename": "customers_01.csv",
      "status": "SUCCESS",
      "totalRecords": 3,
      "successfulRecords": 3,
      "failedRecords": 0
    }
  ]
  ```

#### `GET /api/v1/imports/{importId}`
Retrieves summary information for a previous import run.

#### `GET /api/v1/imports/{importId}/errors`
Retrieves all row-level validation and parse errors for a specific import execution, grouped by filename and row number:
```json
[
  {
    "importId": "9b3889ed-fda6-42cf-ba52-375e6e2775a0",
    "filename": "customers_03.csv",
    "rowNumber": 3,
    "fieldNames": [
      "email",
      "age"
    ],
    "errorMessages": [
      "Field 'email' has invalid format: 'marco@example'",
      "Field 'age' must be a valid integer, got: 'thirty'"
    ],
    "rawData": "5,Marco Rossi,+39000000000,marco@example,thirty,Italy"
  }
]
```

---

### 2. Customer Operations

#### `GET /api/v1/customers`
Retrieves a paginated list of customers.

- **Parameters**:
  - `page` (int, default: `0`): Zero-based page number.
  - `size` (int, default: `20`, min: `1`, max: `100`): Page size.
  - `sortBy` (string, default: `id`): Sort field (`id`, `name`, `email`, `age`, `country`, `phone`).
  - `direction` (string, default: `ASC`): `ASC` or `DESC`.

- **Response** (`200 OK`):
  ```json
  {
    "content": [
      {
        "id": 1,
        "name": "John Smith",
        "email": "john@example.com",
        "age": 35,
        "country": "Portugal",
        "phone": "+351910000000",
        "version": 1
      }
    ],
    "totalElements": 4,
    "totalPages": 2,
    "page": 0,
    "size": 2,
    "isFirst": true,
    "isLast": false
  }
  ```

---

### 3. Export Operations

#### `POST /api/v1/exports`
Generates a downloadable export file of persisted customers.

- **Payload**:
  ```json
  {
    "format": "CSV",
    "columns": ["id", "name", "phone", "email"]
  }
  ```
- **Supported Formats**: `CSV`, `TXT`, `XLSX`.
- **Supported Columns**: `id`, `name`, `email`, `age`, `country`, `phone`.

**Example TXT output:**
```text
NAME       | COUNTRY  | AGE 
----------------------------
John Smith | Portugal | 35  
Jane Doe   | Spain    | 28  
Bob Smith  | France   | 42  
Ana Costa  | Portugal |     
```

---

### 4. Observability & Health

- **`GET /actuator/health`**: Returns detailed health components including database connectivity and custom customer/import metrics:
  ```json
  {
    "status": "UP",
    "components": {
      "application": {
        "status": "UP",
        "details": {
          "service": "csv-management-fs",
          "status": "OPERATIONAL",
          "totalPersistedCustomers": 4,
          "totalExecutedImports": 3
        }
      },
      "db": { "status": "UP" }
    }
  }
  ```
- **`GET /actuator/prometheus`**: Exposes Prometheus metrics (`file_import_count_total`, `file_import_records_total`, `file_export_duration_seconds`).

---

## How to Use Observability

This section illustrates how to utilize the built-in observability features in development and production environments.

### 1. Tracing with Correlation IDs

Every HTTP request is associated with a correlation ID to trace log messages across threads and services.

#### Pass a custom correlation ID:
```bash
curl -i -H "X-Correlation-ID: import-batch-2026-001" \
  -F "files=@samples/customers_01.csv" \
  http://localhost:8081/api/v1/imports
```

#### Observe the response header:
The server returns the correlation ID in the response:
```http
HTTP/1.1 200 OK
X-Correlation-ID: import-batch-2026-001
Content-Type: application/json
```
*(If you omit `X-Correlation-ID`, the server generates a UUID automatically and returns it in the header).*

#### Filter logs by Correlation ID:
Because the correlation ID is registered in SLF4J MDC, you can filter application logs for that exact transaction:
```bash
# In Docker:
docker compose logs app | grep "import-batch-2026-001"

# Output will include the correlation ID on every statement:
# 2026-09-23 23:45:10.123 [http-nio-8080-exec-1] [import-batch-2026-001] INFO  p.p.service.ImportService - Completed import...
```

---

### 2. Health Probes & Business Status

#### Check full system health:
```bash
curl http://localhost:8081/actuator/health
```
Returns overall status, database connectivity, and live business entity counters:
```json
{
  "status": "UP",
  "components": {
    "application": {
      "status": "UP",
      "details": {
        "service": "csv-management-fs",
        "status": "OPERATIONAL",
        "totalPersistedCustomers": 4,
        "totalExecutedImports": 3
      }
    },
    "db": {
      "status": "UP"
    }
  }
}
```

#### Kubernetes Liveness & Readiness Probes:
Use these endpoints for container health checks and rolling deployments:
```bash
# Liveness probe (is the container process healthy?):
curl http://localhost:8081/actuator/health/liveness

# Readiness probe (is the database connection ready to receive traffic?):
curl http://localhost:8081/actuator/health/readiness
```

---

### 3. Querying Prometheus & Actuator Metrics

#### Scrape Prometheus metrics:
```bash
curl -s http://localhost:8081/actuator/prometheus | grep "file_"
```
Sample output:
```text
# HELP file_import_count_total Total number of file import requests
file_import_count_total{status="SUCCESS"} 2.0
file_import_count_total{status="PARTIAL_SUCCESS"} 1.0

# HELP file_import_records_total Total records processed in imports
file_import_records_total{type="successful"} 6.0
file_import_records_total{type="failed"} 1.0

# HELP file_export_duration_seconds Time taken to generate file exports
file_export_duration_seconds_count{format="csv"} 1.0
file_export_duration_seconds_sum{format="csv"} 0.012
```

#### Inspect individual metrics via Actuator JSON API:
You can also inspect specific metrics directly in JSON format:
```bash
# Check import request counts:
curl http://localhost:8081/actuator/metrics/file.import.count

# Check total records processed:
curl http://localhost:8081/actuator/metrics/file.import.records

# Check export duration statistics:
curl http://localhost:8081/actuator/metrics/file.export.duration
```

---

## Import Error Logging

When invalid CSV records are processed during import (e.g. invalid email format or non-integer age), each error is persisted to the database and logged via `log.error`:

```log
2026-09-23T22:59:51.286+01:00 ERROR 15333 --- [csv-management-fs] [main] pt.planet.service.ImportService : Import error line: id=3, importId=08166aef-795a-4030-96b8-4ad1af10c820, rowNumber=3, fieldName='email', errorMessage='Field 'email' has invalid format: 'marco@example'', rawData='5,Marco Rossi,+39000000000,marco@example,thirty,Italy'
2026-09-23T22:59:51.286+01:00 ERROR 15333 --- [csv-management-fs] [main] pt.planet.service.ImportService : Import error line: id=4, importId=08166aef-795a-4030-96b8-4ad1af10c820, rowNumber=3, fieldName='age', errorMessage='Field 'age' must be a valid integer, got: 'thirty'', rawData='5,Marco Rossi,+39000000000,marco@example,thirty,Italy'
```

Each log entry includes:
- `id`: Unique database ID of the error record.
- `importId`: UUID of the import execution.
- `rowNumber`: Line number in the CSV file.
- `fieldName`: Specific column that failed validation.
- `errorMessage`: Detailed descriptive reason for the failure.
- `rawData`: Complete unparsed CSV row content.

---

## Error Handling (RFC 9457)

All API errors adhere to standard RFC 9457 `application/problem+json` format:

```json
{
  "type": "https://planet.pt/problems/invalid-column",
  "title": "Invalid Column",
  "status": 400,
  "detail": "Unsupported export column: 'unknown_col'. Supported columns are: [id, name, email, age, country, phone]",
  "instance": "/api/v1/exports",
  "timestamp": "2026-09-23T20:17:31.219934837Z"
}
```

---

## Running the Application

### Option A: Docker Compose (Recommended)

Spins up the Spring Boot application and a dedicated PostgreSQL database container with health checks:

```bash
# Build the image
docker compose build

# Start the stack in background
docker compose up -d

# View live application logs
docker compose logs -f app

# Tear down the stack
docker compose down
```

The application will be accessible at: `http://localhost:8081`

> [!TIP]
> Docker Compose includes an automated `db-init` one-shot container that ensures the `csvdb` database exists before launching the application, even if using an existing PostgreSQL volume.

### Option B: Local Maven Execution

Requires JDK 25 and Maven:

```bash
# Run tests
mvn clean test

# Package JAR
mvn clean package -DskipTests

# Run JAR (ensure PostgreSQL is running or active profile points to H2)
java -jar target/csv-management-fs-1.0.0.jar
```

---

## Testing & Quality Assurance

The test suite covers unit tests, repository interactions, concurrency locking, and end-to-end multipart API integration tests:

| Test Class | Scope / Focus |
| :--- | :--- |
| `CsvHeaderAnalyzerTest` | Case insensitivity, column aliases, unknown headers, missing required columns |
| `CustomerValidatorTest` | Validation rules (email regex, age bounds, positive ID, length constraints) |
| `ExportStrategyTest` | Correctness of CSV, aligned TXT tables, and Excel XLSX workbooks |
| `ImportServiceTest` | Upsert behavior, partial success tracking, multi-file related data merging, error logging |
| `OptimisticLockingTest` | Concurrent update collisions and version checking |
| `FileImportExportIntegrationTest` | End-to-end multi-part file uploads, pagination, sorting, and error retrieval |

Run the full test suite with:
```bash
mvn clean test
```
All **24 tests** execute cleanly with 0 failures and 0 errors.
