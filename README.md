# CSV Management & File Import/Export Service (`csv-management-fs`)

A production-grade, API-First backend service built natively with **Java 25** and **Spring Boot 3.5.16**. It provides dynamic CSV parsing, multi-file batch imports with data merging, pageable customer queries, dynamic multi-format exports (CSV, TXT, Excel XLSX), robust concurrency control via JPA optimistic locking, and a full observability suite (Micrometer & Prometheus).

---

## Table of Contents
- [Architecture & Design](#architecture--design)
- [Key Features](#key-features)
- [Tech Stack & Dependencies](#tech-stack--dependencies)
- [Sample Data & Files](#sample-data--files)
- [API Reference](#api-reference)
  - [1. Import Operations](#1-import-operations)
  - [2. Customer Operations](#2-customer-operations)
  - [3. Export Operations](#3-export-operations)
  - [4. Observability & Health](#4-observability--health)
- [How to Use Observability](#how-to-use-observability)
  - [1. Tracing with Correlation IDs](#1-tracing-with-correlation-ids)
  - [2. Health Probes & Business Status](#2-health-probes--business-status)
  - [3. Querying Prometheus & Actuator Metrics](#3-querying-prometheus--actuator-metrics)
  - [4. Prometheus & Grafana Monitoring](#4-prometheus--grafana-monitoring)
- [Import Error Logging](#import-error-logging)
- [Resilient Batch Imports](#resilient-batch-imports)
- [Error Handling (RFC 9457)](#error-handling-rfc-9457)
- [Running the Application](#running-the-application)
  - [Option A: Docker Compose (Recommended)](#option-a-docker-compose-recommended)
  - [Option B: Running Locally (Local Profile & VM Options)](#option-b-running-locally-local-profile--vm-options)
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
        └────────────────┬────────────────┘
                         │  /actuator/prometheus
                         ▼
                ┌─────────────────┐
                │   Prometheus    │ (:9090)
                └────────┬────────┘
                         │  PromQL
                         ▼
                ┌─────────────────┐
                │     Grafana     │ (:3000)
                │ (Pre-built Dash)│
                └─────────────────┘
```

---

## Key Features

1. **Dynamic CSV Import Pipeline**:
   - **Header Flexibility**: Scans and detects headers regardless of column order, case sensitivity, or aliases (e.g. `telephone` -> `phone`). Tolerates extraneous unknown columns without failure.
   - **Row-Level Error Isolation**: Invalid rows are caught, persisted in `import_errors`, and logged without aborting the entire batch.
   - **Idempotent Upsert Logic**: Merges incoming customer updates by ID. If a customer already exists, non-empty fields update the record; otherwise, a new record is created.
   - **Multi-File Batch Imports**: Accepts multiple CSV files in a single request, merging related records across files (e.g. basic customer data in file 1 and contact information in file 2).
   - **Fault-Tolerant Batch Imports**: Corrupted, empty, or headerless files within a batch do not abort execution; valid files are committed and invalid files are tracked as `FAILED`.
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
   - Fully compatible with **Java 25**, **MapStruct 1.6.3**, **Project Lombok 1.18.42**, **Lombok MapStruct Binding 0.2.0**, and **Springdoc OpenAPI 2.8.5**.

---

## Tech Stack & Dependencies

| Technology | Version / Description |
| :--- | :--- |
| **Java** | 25 (Temurin 25.0.2 / Class file format 69) |
| **Spring Boot** | 3.5.16 (Web, Data JPA, Validation, Actuator) |
| **API Documentation** | Springdoc OpenAPI 2.8.5 (Swagger UI & OpenAPI 3.0) |
| **Database** | PostgreSQL 16 (H2 in-memory for testing) |
| **Schema Migrations** | Flyway 11.x |
| **Code Generation** | OpenAPI Generator Maven Plugin 7.12.0 |
| **DTO Mapping** | MapStruct 1.6.3 + Lombok MapStruct Binding 0.2.0 |
| **Boilerplate Reduction**| Project Lombok 1.18.42 |
| **File Processing** | Apache Commons CSV 1.12.0, Apache POI 5.4.0 (OOXML) |
| **Metrics** | Micrometer Prometheus Registry |
| **Containerization** | Docker multi-stage build & Docker Compose |

---

## Sample Data & Files

The `samples/` directory provides pre-configured test data illustrating various import scenarios:

| File | Records | Description / Purpose |
| :--- | :---: | :--- |
| `samples/customers_01.csv` | 3 | Standard valid records with headers: `id`, `name`, `email`, `age`, `country`. |
| `samples/customers_02.csv` | 2 | Demonstrates multi-file data merging (`phone` column). Contains 1 valid record and 1 invalid record (empty age). |
| `samples/customers_03.csv` | 2 | Demonstrates column order independence (`id,name,phone,email,age,country`) and multiple field validation errors (`marco@example`, `thirty`). |
| `samples/customers_04.csv` | 1 | Demonstrates extraneous column tolerance (includes an unrecognized `other` column which is safely ignored). |
| `samples/customers_05.csv` | 0 | Empty dataset with headers only. |
| `samples/collections_call_api.har` | - | Exported HTTP Archive (HAR) file containing sample API requests and responses. |

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
  - `sortBy` (string, default: `id`): Sort field (`id`, `name`, `email`, `age`, `country`, `phone`, `createdAt`, `updatedAt`).
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
- **Supported Formats**: `CSV`, `TXT`, `XLSX`, `XLS`.
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
- **`GET /actuator/prometheus`**: Exposes Prometheus metrics:
  - `file_import_count_total` (tags: `status=SUCCESS|PARTIAL_SUCCESS|FAILED`)
  - `file_import_records_total` (tags: `type=successful|failed`)
  - `file_import_duration_seconds` (timer measuring import execution duration)
  - `file_export_count_total` (tags: `format=csv|txt|xlsx|xls`)
  - `file_export_records_total` (tags: `format=csv|txt|xlsx|xls`)
  - `file_export_duration_seconds` (timer measuring export file generation duration)

---

## How to Use Observability

This section illustrates how to utilize the built-in observability features in development and production environments.

> [!NOTE]
> When running with **Docker Compose**, the application is exposed on host port `8081` (`http://localhost:8081`, mapped to container port `8080`). When running **locally via Maven / JAR**, the application defaults to port `8080` (`http://localhost:8080`) as specified in `src/main/resources/application.yml`.

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

### 4. Prometheus, Tempo & Grafana Observability (Metrics & Tracing)

The Docker Compose environment comes with a fully automated, pre-configured observability stack with metrics and distributed tracing:

```
┌─────────────────┐          /actuator/prometheus          ┌─────────────────┐              PromQL              ┌─────────────────┐
│   Spring Boot   │ ─────────────────────────────────────► │   Prometheus    │ ────────────────────────────────► │     Grafana     │
│   (Port 8081)   │                                        │   (Port 9090)   │                                   │   (Port 3000)   │
└─────────────────┘                                        └─────────────────┘                                   └─────────────────┘
         │                                                                                                                ▲
         │                      OTLP / HTTP (4318)         ┌─────────────────┐             TraceQL / Spans                │
         └───────────────────────────────────────────────► │  Grafana Tempo  │ ───────────────────────────────────────────┘
                                                           │   (Port 3200)   │
                                                           └─────────────────┘
```

#### Accessing Prometheus:
- **URL**: [http://localhost:9090](http://localhost:9090)
- **Targets Page**: [http://localhost:9090/targets](http://localhost:9090/targets) (confirms `csv-management` target is `UP` scraping `app:8080/actuator/prometheus`).
- **Sample PromQL Queries**:
  - Total Imports: `sum(file_import_count_total)`
  - Import Request Rate: `sum by (status) (rate(file_import_count_total[1m]))`
  - Records Processed vs Failed: `sum by (type) (rate(file_import_records_total[1m]))`
  - Average Import Duration: `rate(file_import_duration_seconds_sum[1m]) / rate(file_import_duration_seconds_count[1m])`
  - Export Request Rate: `sum by (format) (rate(file_export_count_total[1m]))`

#### Accessing Grafana Tempo (Distributed Tracing):
- **URL**: [http://localhost:3200](http://localhost:3200) (Tempo HTTP endpoint)
- **OTLP Endpoints**:
  - HTTP: `http://localhost:4318/v1/traces` (used by Spring Boot Actuator & OpenTelemetry exporter)
  - gRPC: `localhost:4317`
- **TraceQL Query Examples** in Grafana:
  - All traces: `{}`
  - Spans for this service: `{ resource.service.name = "csv-management-fs" }`
  - Only errors: `{ status = error }`
  - Slow operations (>100ms): `{ duration > 100ms }`
  - Import operations: `{ name =~ ".*import.*" }`
  - Export operations: `{ name =~ ".*export.*" }`

#### Accessing Grafana:
- **URL**: [http://localhost:3000](http://localhost:3000)
- **Default Credentials**: `admin` / `admin`
- **Datasource Provisioning**: Automatically pre-configured with:
  - **Prometheus** (`uid: Prometheus`) with exemplars linked to Tempo.
  - **Tempo** (`uid: tempo`) with TraceQL support, node graphs, and traces-to-metrics correlation.
- **Pre-Built Dashboard**: **CSV Management - Service Observability** is automatically provisioned and ready on startup under the **CSV Management** folder. It includes:
  - **KPI Stat Cards**: Total Imports, Successful Imports, Failed/Partial Imports, Total Records Processed, Total Exports, Exported Records.
  - **Import Analytics**: Real-time import rate by status (`SUCCESS`, `PARTIAL_SUCCESS`, `FAILED`), processed vs. failed record rates, and average & max duration timers (with **exemplars** enabled).
  - **Export Analytics**: Multi-format export rates (CSV, TXT, XLSX), exported record counts, and export duration timers (with **exemplars** enabled).
  - **JVM & Infrastructure**: Heap memory usage (Used / Committed / Max), process vs system CPU utilization, and HikariCP connection pool states (Active, Idle, Pending).
  - **Distributed Tracing (Tempo)**:
    - **Live Distributed Traces Table**: Search, filter, and inspect traces directly inside the dashboard. Clicking any trace expands the complete waterfall span tree showing individual steps (`import-csv-files`, `process-single-file`, `export-customers`).
    - **Exemplar Integration**: Click on exemplar dots directly inside the duration charts to navigate to that specific request's trace.
    - **Direct Explore Button**: Shortcut at the top of the dashboard to jump into Grafana Explore with Tempo preloaded.

---

## Import Error Logging

When invalid CSV records are processed during import (e.g. invalid email format or non-integer age), each error is persisted to the database and logged via `log.error`:

```log
2026-09-24 12:29:53.915 [http-nio-8080-exec-1] [fdf0db1a-6fb9-4b1b-84f6-e3ebec05efe7] ERROR pt.planet.service.ImportService - Import error line: id=6, importId=fdf0db1a-6fb9-4b1b-84f6-e3ebec05efe7, filename='customers_03.csv', rowNumber=3, fieldName='email', errorMessage='Field 'email' has invalid format: 'marco@example'', rawData='5,Marco Rossi,+39000000000,marco@example,thirty,Italy'
2026-09-24 12:29:53.915 [http-nio-8080-exec-1] [fdf0db1a-6fb9-4b1b-84f6-e3ebec05efe7] ERROR pt.planet.service.ImportService - Import error line: id=7, importId=fdf0db1a-6fb9-4b1b-84f6-e3ebec05efe7, filename='customers_03.csv', rowNumber=3, fieldName='age', errorMessage='Field 'age' must be a valid integer, got: 'thirty'', rawData='5,Marco Rossi,+39000000000,marco@example,thirty,Italy'
```

Each log entry includes:
- `id`: Unique database ID of the error record.
- `importId`: UUID of the import execution.
- `filename`: Source CSV filename where the error occurred.
- `rowNumber`: Line number in the CSV file (`0` for file-level or structural failures).
- `fieldName`: Specific column that failed validation (`file` for structural failures).
- `errorMessage`: Detailed descriptive reason for the failure.
- `rawData`: Complete unparsed CSV row content.

---

## Resilient Batch Imports

When uploading multiple CSV files in a single batch request (`POST /api/v1/imports`), the service implements **fault-tolerant execution**:

- **Transaction Isolation**: Each file executes within its own independent database transaction. A failure in one file never rolls back or aborts valid files in the same batch.
- **Non-blocking Loop**: If an individual file is invalid (e.g., empty file, missing header row, no data rows, or unreadable format), the service logs a warning and an error, records the failure, and continues processing the remaining files.
- **Audit & Traceability**: Each invalid file is persisted in `imports` with status `FAILED` (`totalRecords: 0`, `successfulRecords: 0`, `failedRecords: 0`), and its failure reason is logged and stored in `import_errors` (`rowNumber: 0`, `fieldName: "file"`).
- **Comprehensive API Response**: The API returns HTTP `200 OK` with an array of `ImportResponse` objects corresponding 1-to-1 to each uploaded file. Clients can query `GET /api/v1/imports/{importId}/errors` to inspect the exact reason for any failed file.

### Example Response for Mixed Batch:
When uploading `customers_01.csv` (valid), `empty.csv` (empty file), and `customers_02.csv` (partially valid):

```json
[
  {
    "importId": "9b3889ed-fda6-42cf-ba52-375e6e2775a0",
    "filename": "customers_01.csv",
    "status": "SUCCESS",
    "totalRecords": 3,
    "successfulRecords": 3,
    "failedRecords": 0
  },
  {
    "importId": "2fd50bb4-37c3-4a99-abab-3b0588f24ead",
    "filename": "empty.csv",
    "status": "FAILED",
    "totalRecords": 0,
    "successfulRecords": 0,
    "failedRecords": 0
  },
  {
    "importId": "e1f67819-1203-47b9-ab1d-4edfb0aa19a0",
    "filename": "customers_02.csv",
    "status": "PARTIAL_SUCCESS",
    "totalRecords": 2,
    "successfulRecords": 1,
    "failedRecords": 1
  }
]
```

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

### Problem Types Handled

| Problem Type URI | HTTP Status | Description |
| :--- | :---: | :--- |
| `https://planet.pt/problems/invalid-file` | `400 Bad Request` | Unparseable CSV file, missing request payload, or unsupported file format |
| `https://planet.pt/problems/invalid-column` | `400 Bad Request` | Requested export column is unrecognized or empty |
| `https://planet.pt/problems/invalid-import-record` | `400 Bad Request` | Malformed or invalid record encountered during import |
| `https://planet.pt/problems/validation-error` | `400 Bad Request` | Request parameters failed validation constraints |
| `https://planet.pt/problems/file-size-exceeded` | `400 Bad Request` | Uploaded file exceeds the configured maximum multipart size (50MB) |
| `https://planet.pt/problems/customer-not-found` | `404 Not Found` | Import execution ID or customer resource does not exist |
| `https://planet.pt/problems/concurrent-update-conflict` | `409 Conflict` | Optimistic locking collision detected during concurrent modifications (`@Version`) |
| `https://planet.pt/problems/import-processing-error` | `500 Internal Server Error` | Unrecoverable I/O or parsing failure during import execution |
| `https://planet.pt/problems/internal-error` | `500 Internal Server Error` | Generic unexpected internal server exception |

---

## Running the Application

### Option A: Docker Compose (Recommended)

Spins up the Spring Boot application and a dedicated PostgreSQL database container with health checks:

```bash
# Build the images
docker compose build

# Start the stack in background
docker compose up -d

# View live application logs
docker compose logs -f app

# Tear down the stack
docker compose down
```

#### Exposed Endpoints & Services:

| Service | Container Name | Host Port | Internal URL | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| **Spring Boot App** | `csv-management-app` | `8081` | `http://app:8080` | REST API, Swagger UI, Actuator |
| **PostgreSQL** | `csv-postgres` | `5432` | `postgres:5432` | Primary database (`csvdb`) |
| **Tempo** | `csv-tempo` | `3200`, `4317`, `4318` | `http://tempo:3200` | Distributed Tracing Backend (OTLP) |
| **Prometheus** | `csv-prometheus` | `9090` | `http://prometheus:9090` | Time-series metrics scraper |
| **Grafana** | `csv-grafana` | `3000` | `http://grafana:3000` | Dashboards & Trace Explorer (`admin`/`admin`) |

- **App & Swagger UI**: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)
- **Actuator Health**: [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)
- **Grafana Dashboard & Traces**: [http://localhost:3000](http://localhost:3000) (admin / admin)
- **Grafana Tempo Endpoint**: [http://localhost:3200](http://localhost:3200)
- **Prometheus Dashboard**: [http://localhost:9090](http://localhost:9090)

> [!TIP]
> Docker Compose includes an automated `db-init` one-shot container that ensures the `csvdb` database exists before launching the application, even if using an existing PostgreSQL volume.

### Option B: Running Locally (Local Profile & VM Options)

When running the application locally outside of Docker (via terminal or your preferred IDE), you **must activate the `local` profile** by setting the VM option:

```bash
-Dspring.profiles.active=local
```

#### Why is `-Dspring.profiles.active=local` required?

1. **Loads Local Configuration**: Activates [`src/main/resources/application-local.yml`](file:///Users/marcosferreira/Documents/csv-mangement-fs/src/main/resources/application-local.yml).
2. **Database Routing**: Points the PostgreSQL connection to `localhost:5432` (`jdbc:postgresql://localhost:5432/csvdb`) with default credentials (`postgres`/`postgres`).
3. **Tracing Compatibility**: In the base profile (`application.yml`), OTLP tracing targets `http://tempo:4318`. In `application-local.yml`, tracing export is disabled by default (`MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED: false`), preventing connection errors when Tempo is not running locally.
4. **Port Configuration**: Exposes the application directly on port `8080` (accessible at `http://localhost:8080`).

---

#### 1. Running via Terminal / Command Line

Requires JDK 25 and Maven:

```bash
# Optional: Spin up PostgreSQL only (if not already running natively)
docker compose up -d postgres db-init

# Method 1: Run directly with the Spring Boot Maven Plugin
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=local"

# Method 2: Package and run JAR
mvn clean package -DskipTests
java -Dspring.profiles.active=local -jar target/csv-management-fs-1.0.0.jar
```

---

#### 2. Running via IDE (VM Options Configuration)

If running directly from your IDE by launching [`Application.java`](file:///Users/marcosferreira/Documents/csv-mangement-fs/src/main/java/pt/planet/Application.java) (`pt.planet.Application`):

##### **IntelliJ IDEA**
1. Open **Run/Debug Configurations** (`Run` > `Edit Configurations...`).
2. Select your Spring Boot configuration for `Application`.
3. Click **Modify options** (or `Alt+V` / `Cmd+V`) and select **Add VM options**.
4. In the **VM options** field, enter:
   ```text
   -Dspring.profiles.active=local
   ```
   *(Alternatively, enter `local` in the **Active profiles** field).*
5. Click **Apply** and **Run/Debug**.

##### **Visual Studio Code (VS Code)**
Add or update your `.vscode/launch.json` configuration:
```json
{
  "type": "java",
  "name": "Launch Application (Local)",
  "request": "launch",
  "mainClass": "pt.planet.Application",
  "vmArgs": "-Dspring.profiles.active=local"
}
```

##### **Eclipse / Spring Tool Suite (STS)**
1. Right-click project > **Run As** > **Run Configurations...**.
2. Select **Spring Boot App** > `Application`.
3. Open the **Arguments** tab.
4. In **VM arguments**, append:
   ```text
   -Dspring.profiles.active=local
   ```
5. Click **Apply** and **Run**.

---

#### Local Endpoints:
- **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Actuator Health**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- **Actuator Prometheus**: [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)

---

## Testing & Quality Assurance

The test suite covers unit tests, repository interactions, concurrency locking, and end-to-end multipart API integration tests:

| Test Class | Tests  | Scope / Focus |
| :--- |:------:| :--- |
| `CsvHeaderAnalyzerTest` |   7    | Case insensitivity, column aliases, unknown headers, missing required columns, whitespace trimming |
| `CustomerValidatorTest` |   8    | Validation rules (email regex, age bounds, positive ID, length constraints) |
| `ExportStrategyTest` |   3    | Correctness of CSV, aligned TXT tables, and Excel XLSX workbooks |
| `ExportServiceTest` |   10   | Export business logic, strategy routing, column validation, metrics recording, and mock data tests |
| `ImportServiceTest` |   7    | Upsert behavior, partial success tracking, resilient batch imports with invalid files, multi-file data merging, error logging |
| `OptimisticLockingTest` |   1    | Concurrent update collisions and version checking via `@Version` |
| `FileImportExportIntegrationTest` |   5    | End-to-end multi-part file uploads, resilient batch imports, pagination, sorting, and error retrieval |
| **Total** | **41** | **100% passing test suite** |

Run the full test suite with:
```bash
mvn clean test
```
All **41 tests** execute cleanly with 0 failures and 0 errors.
