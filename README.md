# CSV Management & File Import/Export Service (`csv-management-fs`)

A production-grade, API-First, event-driven backend ecosystem built natively with **Java 25** and **Spring Boot 3.5.16**. It provides dynamic CSV parsing, multi-file batch imports with data merging, pageable customer queries, dynamic multi-format exports (CSV, TXT, Excel XLSX), robust concurrency control via JPA optimistic locking, and a full observability suite (Micrometer, Prometheus, Tempo & Grafana).

The system features an **Asynchronous Event-Driven Export Architecture**: export requests are accepted immediately (HTTP `202 Accepted`), processed in the background using memory-bounded keyset pagination, persisted to **AWS S3** (or **LocalStack** locally), and announced via **RabbitMQ** to a dedicated **Notification Microservice** (`notification-service`) that generates secure pre-signed download URLs and delivers responsive HTML notification emails via **Gmail SMTP**.

---

## Table of Contents
- [Architecture & Design](#architecture--design)
  - [System Flow & Microservices Diagram](#system-flow--microservices-diagram)
  - [Core Architectural Decisions](#core-architectural-decisions)
- [Key Features](#key-features)
- [Tech Stack & Dependencies](#tech-stack--dependencies)
- [Sample Data & Files](#sample-data--files)
- [API Reference](#api-reference)
  - [1. Import Operations](#1-import-operations)
  - [2. Customer Operations](#2-customer-operations)
  - [3. Asynchronous Export Operations](#3-asynchronous-export-operations)
  - [4. Notification Service API](#4-notification-service-api)
  - [5. Observability & Health](#5-observability--health)
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

The ecosystem is architected around two decoupled microservices cooperating over an AMQP message broker and cloud object storage:

1. **`csv-management-app`** (Port `8081`): The primary business service managing customer records, CSV imports, batch reconciliation, and keyset-paginated background export file generation.
2. **`notification-service`** (Port `8082`): An autonomous Spring Boot microservice consuming export completion events from RabbitMQ, formatting rich HTML notification templates, dispatching emails via Gmail SMTP, and keeping an in-memory audit trail.

### System Flow & Microservices Diagram

```mermaid
flowchart TD
    subgraph Client ["Client Layer"]
        C[HTTP Client / Frontend]
    end

    subgraph CoreService ["csv-management-app (Port 8081)"]
        EC[ExportController<br/>POST /api/v1/exports]
        ES[ExportService<br/>@Async Keyset Worker]
        PG[(PostgreSQL<br/>customers table)]
        S3Client[AwsS3StorageService<br/>AWS SDK v2 S3Client]
        RMP[RabbitMQNotificationProducer<br/>RabbitTemplate]
    end

    subgraph Storage ["Cloud / Object Storage"]
        S3[(AWS S3 / LocalStack<br/>bucket: customer-exports)]
    end

    subgraph Messaging ["Message Broker"]
        RMQ{{RabbitMQ<br/>DirectExchange: csv.export.exchange<br/>Queue: notification-service}}
    end

    subgraph NotifService ["notification-service (Port 8082)"]
        NL[ExportNotificationListener<br/>@RabbitListener]
        MS[MailService<br/>JavaMailSender]
        HS[NotificationHistoryService<br/>In-Memory Audit Store]
        NC[NotificationController<br/>GET /api/v1/notifications]
    end

    subgraph External ["Email Relay"]
        SMTP[Gmail SMTP Relay<br/>smtp.gmail.com:465 SSL]
        INBOX[User Email Inbox]
    end

    C -- "1. POST /api/v1/exports (Header: X-User-Email)" --> EC
    EC -- "2. HTTP 202 Accepted (ExportAsyncResponse)" --> C
    EC -. "Async handoff" .-> ES
    ES -- "3. Keyset Pagination (WHERE id > :lastId)" --> PG
    ES -- "4. Upload File" --> S3Client
    S3Client -- "5. PutObject" --> S3
    S3Client -- "6. Generate Pre-signed URL (24h)" --> ES
    ES -- "7. Publish ExportNotificationMessage" --> RMP
    RMP -- "8. amqp.basicPublish" --> RMQ
    RMQ -- "9. Push Message" --> NL
    NL -- "10. Format Responsive HTML Email" --> MS
    NL -- "11. Record Notification" --> HS
    MS -- "12. Dispatch MIME Email" --> SMTP
    SMTP -- "13. Deliver Email with Download Button" --> INBOX
    INBOX -- "14. Click S3 Pre-signed URL" --> S3
    C -. "Query Audit History" .-> NC
```

### Core Architectural Decisions

- **API-First / Contract-First**: Defined via OpenAPI 3.0 specification (`src/main/resources/openapi/api-spec.yaml`), automatically generating interfaces and DTOs.
- **Asynchronous Execution Pattern**: Export requests detach immediately with HTTP `202 Accepted` returning tracking UUIDs, preventing HTTP client timeouts on massive datasets.
- **Event-Driven Decoupling**: File generation and email delivery are separated across RabbitMQ, isolating SMTP latency and network flakiness from the primary transactional database.
- **Stateless Cloud Storage**: Exported files are uploaded directly to S3/LocalStack rather than local container filesystems, enabling seamless horizontal container scaling.
- **Ephemeral Pre-Signed URLs**: Download links expire securely after 24 hours without exposing long-lived AWS IAM credentials.
- **Distributed Observability**: Every request is tagged with an `X-Correlation-ID` and traced across Tomcat, PostgreSQL, and background tasks through Micrometer, Prometheus, OpenTelemetry, and Grafana Tempo.

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

3. **Asynchronous Multi-Format Export Engine with Keyset Pagination**:
   - **Immediate Client Detach (HTTP 202)**: Returns `ExportAsyncResponse` with tracking UUID and file details immediately.
   - **Keyset (Cursor-Based) Pagination Pattern**: Eliminates `OutOfMemoryError` on large tables by retrieving records in bounded batches (`WHERE id > :lastId ORDER BY id ASC LIMIT :batchSize`) using primary key B-Tree index seeks ($O(\log N)$).
   - **Hibernate Persistence Context Eviction**: Calls `entityManager.clear()` after each batch to prevent first-level cache accumulation.
   - **Strategy Pattern Formats**:
     - **CSV**: Standard comma-separated format via Apache Commons CSV streamed in batches.
     - **TXT**: Formatted tabular text report with aligned columns streamed via `BufferedWriter`.
     - **XLSX**: Styled Microsoft Excel spreadsheet with headers and auto-sized columns via Apache POI's streaming `SXSSFWorkbook` (100-row memory window).

4. **AWS S3 Object Storage & Ephemeral Pre-Signed URLs**:
   - Generates files directly to temporary storage, uploads to **AWS S3** (or **LocalStack** locally via AWS SDK v2), and deletes the local temporary file.
   - Creates secure pre-signed download URLs with a 24-hour expiration (`S3Presigner`).
   - Configurable dual-endpoint resolution: uploads using internal Docker container endpoints while issuing download URLs with external public endpoints (e.g. `http://localhost:4566`).

5. **RabbitMQ Event Bus & Message Broker**:
   - Asynchronous export workers publish `ExportNotificationMessage` events to RabbitMQ `DirectExchange` (`csv.export.exchange`) on routing key `notification.email`.
   - Durable queue binding (`notification-service`) with JSON message conversion via Jackson.

6. **Dedicated Notification Microservice (`notification-service`)**:
   - Autonomous Spring Boot consumer listening to the `notification-service` queue.
   - Renders responsive HTML notification emails featuring export metadata (format, record count, execution UUID) and an attractive call-to-action button linking to the pre-signed S3 download URL.
   - Dispatches emails through **Gmail SMTP** (port 465 SSL, StartTLS, and App Password authentication).
   - In-memory thread-safe notification audit service with REST query endpoints (`GET /api/v1/notifications`).

7. **Data Integrity & Concurrency**:
   - Optimistic locking via JPA `@Version` on `CustomerEntity` guarantees detection of concurrent write collisions.

8. **Observability & Distributed Tracing**:
   - Request tracking via `X-Correlation-ID` header and SLF4J MDC.
   - Custom Micrometer counters and timers for import counts, processed record metrics, and export durations.
   - Actuator health probes (`/actuator/health/liveness`, `/actuator/health/readiness`).
   - Prometheus scrape endpoint at `/actuator/prometheus` and distributed tracing via OpenTelemetry / Grafana Tempo (`/actuator`).

9. **Modern Tooling & Compatibility**:
   - Fully compatible with **Java 25**, **MapStruct 1.6.3**, **Project Lombok 1.18.42**, **Lombok MapStruct Binding 0.2.0**, and **Springdoc OpenAPI 2.8.5**.

---

## Tech Stack & Dependencies

| Technology | Version / Description | Purpose |
| :--- | :--- | :--- |
| **Java** | 25 (Temurin 25.0.2 / Class file format 69) | Core programming language |
| **Spring Boot** | 3.5.16 (Web, Data JPA, Validation, Actuator, AMQP, Mail) | Framework & runtime |
| **API Documentation** | Springdoc OpenAPI 2.8.5 (Swagger UI & OpenAPI 3.0) | Interactive API explorer |
| **Database** | PostgreSQL 16 (H2 in-memory for testing) | Relational database |
| **Message Broker** | RabbitMQ 3.13 (Management Alpine) | Event bus for asynchronous export notifications |
| **Object Storage** | AWS S3 / LocalStack 3.8 | Storage for generated exports & pre-signed download URLs |
| **AWS SDK** | AWS SDK for Java v2 (2.31.25 - S3 & S3Presigner) | S3 client and pre-signed URL generator |
| **Email Relay** | Spring Boot Mail / Jakarta Mail 3.5.16 | SMTP email delivery via Gmail |
| **Schema Migrations** | Flyway 11.x | Database versioning |
| **Code Generation** | OpenAPI Generator Maven Plugin 7.12.0 | Contract-first controller and DTO generation |
| **DTO Mapping** | MapStruct 1.6.3 + Lombok MapStruct Binding 0.2.0 | Fast type-safe bean mapping |
| **Boilerplate Reduction**| Project Lombok 1.18.42 | Compile-time bytecode generation |
| **File Processing** | Apache Commons CSV 1.12.0, Apache POI 5.4.0 (OOXML) | CSV, TXT, and Excel generation |
| **Observability** | Micrometer, Prometheus, OpenTelemetry, Grafana Tempo | Metrics, dashboards, and distributed tracing |
| **Containerization** | Docker multi-stage builds & Docker Compose | Multi-container orchestration |

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

### 3. Asynchronous Export Operations

#### `POST /api/v1/exports`
Triggers an asynchronous background export of customer records. The client receives an immediate `202 Accepted` response with a unique tracking UUID while processing continues in the background.

- **HTTP Method**: `POST /api/v1/exports`
- **Request Headers**:
  - `Content-Type: application/json`
  - `X-User-Email` *(optional, string)*: Recipient email address to be notified when the export finishes (e.g. `user@example.com`).
- **Payload**:
  ```json
  {
    "format": "CSV",
    "columns": ["id", "name", "phone", "email", "country"]
  }
  ```
- **Supported Formats**: `CSV`, `TXT`, `XLSX`, `XLS`.
- **Supported Columns**: `id`, `name`, `email`, `age`, `country`, `phone`.
- **Response** (`202 Accepted`):
  ```json
  {
    "exportId": "32292f10-5039-43f1-b4d0-d3326404016c",
    "message": "Request sent to generate file with extension .csv",
    "format": "CSV",
    "email": "user@example.com"
  }
  ```

#### What Happens in the Background:
1. **Keyset Query**: An asynchronous worker thread executes cursor-based queries against PostgreSQL (`WHERE id > :lastId ORDER BY id ASC LIMIT 1000`).
2. **File Generation**: The strategy formats the stream to disk (`customers_<timestamp>.<ext>`).
3. **S3 Upload**: The file is uploaded to the AWS S3 / LocalStack bucket (`customer-exports`) with metadata.
4. **Pre-signed URL**: An ephemeral, secure pre-signed download URL (24h TTL) is generated.
5. **RabbitMQ Event**: An `ExportNotificationMessage` is published to RabbitMQ direct exchange `csv.export.exchange` on routing key `notification.email`.
6. **Notification Delivery**: The `notification-service` consumes the message and dispatches a branded HTML email via Gmail SMTP containing the download link directly to the recipient.

#### Keyset (Cursor-Based) Pagination Pattern in Exports

To prevent `OutOfMemoryError` and memory spikes when exporting large databases, the export engine avoids `findAll()` in-memory buffering and instead uses **Keyset Pagination (Seek Method)**:

1. **Indexed Seek Query ($O(\log N)$):**
   ```sql
   -- Initial batch:
   SELECT * FROM customers ORDER BY id ASC LIMIT 1000;

   -- Subsequent batches (using cursor):
   SELECT * FROM customers WHERE id > :lastSeenId ORDER BY id ASC LIMIT 1000;
   ```
   Unlike `OFFSET ... LIMIT` (which suffers from linear $O(N)$ slowdown), keyset pagination performs an immediate B-Tree index seek on the primary key, remaining sub-millisecond fast regardless of whether reading the 1st or 1,000,000th row.

2. **Persistence Context Eviction:**
   After each batch of 1,000 records is processed, `entityManager.clear()` is called to evict entities from the Hibernate 1st-level cache, preventing JVM heap bloat.

3. **Streaming Strategies:**
   - **CSV**: Writes records batch-by-batch using Apache Commons `CSVPrinter`.
   - **TXT**: Formats tabular lines directly to a `BufferedWriter` on the output stream.
   - **XLSX**: Uses Apache POI's streaming `SXSSFWorkbook(100)`, keeping only a 100-row sliding window in memory while flushing excess rows to temporary disk storage.

4. **Configuration:**
   The batch size can be tuned in `application.yml`:
   ```yaml
   app:
     export:
       batch-size: 1000
   ```

---

### 4. Notification Service API

The standalone **Notification Microservice** (`http://localhost:8082`) provides administrative and audit endpoints to monitor delivered notifications:

#### `GET /api/v1/notifications`
Retrieves recent email notification delivery records (latest first):
```json
[
  {
    "notificationId": "9e87a972-6a54-4e96-9520-db60290b2810",
    "exportId": "30bf36a5-f912-4919-9d4c-9febcc831a3d",
    "recipientEmail": "user@example.com",
    "filename": "customers_20260926_213029.xlsx",
    "format": "XLSX",
    "recordCount": 4,
    "presignedUrl": "http://localhost:4566/customer-exports/exports/customers_20260926_213029.xlsx?X-Amz-Algorithm=AWS4-HMAC-SHA256&...",
    "status": "SENT",
    "errorMessage": null,
    "processedAt": "2026-09-26T21:30:32.397657927Z"
  }
]
```

#### `GET /api/v1/notifications/{exportId}`
Fetches the notification audit record for a specific export UUID. Returns `404 Not Found` if no notification was dispatched for that export ID.

#### `POST /api/v1/notifications/send-test`
Dispatches an immediate test email through the active SMTP relay without triggering an export:
- **Query Param or JSON Body**: `email` (e.g. `POST /api/v1/notifications/send-test?email=your-email@example.com`)
- **Response** (`200 OK`):
  ```json
  {
    "exportId": "18695ee3-6d57-4e29-ad84-fff766db9b8b",
    "presignedUrl": "http://localhost:4566/customer-exports/exports/customers_test_1790457621025.csv?test=true",
    "status": "SUCCESS",
    "message": "Test notification email sent to your-email@example.com"
  }
  ```

---

### 5. Observability & Health

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

Spins up the complete event-driven microservices ecosystem including PostgreSQL, RabbitMQ, LocalStack S3, both Spring Boot microservices, and observability tooling:

```bash
# Build the microservice images
docker compose build

# Start the entire ecosystem in the background
docker compose up -d

# View live logs of both microservices
docker compose logs -f app notification-service

# Tear down the stack and volumes
docker compose down
```

#### Exposed Endpoints & Services:

| Service | Container Name | Host Port | Internal URL | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| **Customer App** | `csv-management-app` | `8081` | `http://app:8080` | REST API, Swagger UI, Batch & Keyset Engine |
| **Notification Service** | `csv-notification-service` | `8082` | `http://notification-service:8080` | RabbitMQ Consumer, HTML Email Dispatcher, Audit API |
| **RabbitMQ** | `csv-rabbitmq` | `5672`, `15672` | `amqp://rabbitmq:5672` | Event Message Broker & Web Management UI (`guest`/`guest`) |
| **LocalStack (AWS S3)** | `csv-localstack` | `4566` | `http://localstack:4566` | Emulated S3 Object Storage (`customer-exports` bucket) |
| **PostgreSQL** | `csv-postgres` | `5432` | `postgres:5432` | Primary database (`csvdb`) |
| **Tempo** | `csv-tempo` | `3200`, `4317`, `4318` | `http://tempo:3200` | Distributed Tracing Backend (OTLP) |
| **Prometheus** | `csv-prometheus` | `9090` | `http://prometheus:9090` | Time-series metrics scraper |
| **Grafana** | `csv-grafana` | `3000` | `http://grafana:3000` | Dashboards & Trace Explorer (`admin`/`admin`) |

#### Quick Links:
- **Customer App & Swagger UI**: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)
- **Notification Service API**: [http://localhost:8082/api/v1/notifications](http://localhost:8082/api/v1/notifications)
- **RabbitMQ Management Dashboard**: [http://localhost:15672](http://localhost:15672) *(Credentials: guest / guest)*
- **LocalStack S3 Bucket Health**: [http://localhost:4566/_localstack/health](http://localhost:4566/_localstack/health)
- **Actuator Health (App)**: [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)
- **Actuator Health (Notification Service)**: [http://localhost:8082/actuator/health](http://localhost:8082/actuator/health)
- **Grafana Dashboards & Traces**: [http://localhost:3000](http://localhost:3000) *(Credentials: admin / admin)*
- **Prometheus Dashboard**: [http://localhost:9090](http://localhost:9090)

> [!TIP]
> The `docker-compose.yml` pre-configures real Gmail SMTP relay settings (`smtp.gmail.com:465` with SSL enabled) and automatically initializes the `customer-exports` S3 bucket inside LocalStack upon startup.

### Option B: Running Locally (Local Profile & VM Options)

When running the applications locally outside of Docker (via terminal or IDE), activate the `local` profile:

```bash
-Dspring.profiles.active=local
```

#### Why is `-Dspring.profiles.active=local` required?

1. **Loads Local Configuration**: Activates [`src/main/resources/application-local.yml`](file:///Users/marcosferreira/Documents/csv-mangement-fs/src/main/resources/application-local.yml).
2. **Database Routing**: Points PostgreSQL to `localhost:5432` (`jdbc:postgresql://localhost:5432/csvdb`).
3. **RabbitMQ & S3 Local Routing**: Points RabbitMQ to `localhost:5672` and AWS S3 endpoint to `http://localhost:4566`.
4. **Tracing Compatibility**: Disables remote OTLP export when Tempo is not running locally.
5. **Port Configuration**: Binds `csv-management-app` to port `8080` (or `8081` in Docker).

---

#### 1. Running via Terminal / Command Line

Requires JDK 25 and Maven:

```bash
# 1. Start support infrastructure (Postgres, RabbitMQ, LocalStack)
docker compose up -d postgres db-init rabbitmq localstack

# 2. Run the main customer application
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=local"

# 3. (Optional) Run the notification service in a separate terminal
cd notification-service
mvn spring-boot:run
```

---

#### 2. Running via IDE (VM Options Configuration)

If running directly from your IDE by launching [`Application.java`](file:///Users/marcosferreira/Documents/csv-mangement-fs/src/main/java/pt/planet/Application.java):

##### **IntelliJ IDEA**
1. Open **Run/Debug Configurations** (`Run` > `Edit Configurations...`).
2. Select your Spring Boot configuration for `Application`.
3. Click **Modify options** (or `Alt+V` / `Cmd+V`) and select **Add VM options**.
4. In the **VM options** field, enter:
   ```text
   -Dspring.profiles.active=local
   ```
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

---

## Testing & Quality Assurance

The ecosystem maintains comprehensive test coverage across both microservices, verifying unit behavior, keyset traversal, persistence context cache eviction, S3 uploads, RabbitMQ message serialisation, and MIME email templating:

### Primary App Suite (`csv-management-fs`):

| Test Class | Tests  | Scope / Focus |
| :--- |:------:| :--- |
| `CsvHeaderAnalyzerTest` |   7    | Case insensitivity, column aliases, unknown headers, missing required columns, whitespace trimming |
| `CustomerValidatorTest` |   8    | Validation rules (email regex, age bounds, positive ID, length constraints) |
| `ExportStrategyTest` |   3    | Correctness of CSV, aligned TXT tables, and Excel XLSX workbooks |
| `ExportServiceTest` |   15   | Keyset pagination multi-batch traversal, cache eviction (`entityManager.clear()`), strategy routing, column validation, S3 upload invocation, and RabbitMQ event publishing |
| `ImportServiceTest` |   7    | Upsert behavior, partial success tracking, resilient batch imports with invalid files, multi-file data merging, error logging |
| `OptimisticLockingTest` |   1    | Concurrent update collisions and version checking via `@Version` |
| `FileImportExportIntegrationTest` |   5    | End-to-end multi-part file uploads, resilient batch imports, pagination, sorting, and error retrieval |
| `Subtotal (Core App)` | **47** | **100% passing test suite** |

### Notification Service Suite (`notification-service`):

| Test Class | Tests  | Scope / Focus |
| :--- |:------:| :--- |
| `ExportNotificationListenerTest` |   3    | `@RabbitListener` payload consumption, audit persistence, missing recipient skipping |
| `MailServiceTest` |   2    | Multipart MIME message creation, HTML template rendering, pre-signed link injection, simulation mode |
| `NotificationControllerTest` |   3    | REST endpoints (`GET /api/v1/notifications`, `GET /api/v1/notifications/{id}`, `POST /send-test`) |
| `Subtotal (Notification Service)` | **8** | **100% passing test suite** |

### Total Passing Tests: **55 tests** (0 failures, 0 errors, 0 skipped)

Run tests for the whole project:
```bash
# Run core app tests (47 tests)
mvn clean test

# Run notification service tests (8 tests)
mvn -f notification-service/pom.xml clean test
```
