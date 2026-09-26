# Notification Service (`notification-service`)

A dedicated, autonomous event-driven Spring Boot microservice built with **Java 25** and **Spring Boot 3.5.16**. It listens to asynchronous file export events emitted across RabbitMQ, renders branded responsive HTML notification emails with secure pre-signed Amazon S3 download links, and delivers them via **Gmail SMTP Relay** (SSL on port 465).

---

## Architecture Overview

```
                      ┌───────────────────────────────────────────────┐
                      │    RabbitMQ (Exchange: csv.export.exchange)   │
                      └───────────────────────┬───────────────────────┘
                                              │ routingKey: notification.email
                                              ▼
                      ┌───────────────────────────────────────────────┐
                      │         Queue: 'notification-service'        │
                      └───────────────────────┬───────────────────────┘
                                              │
                      ┌───────────────────────▼───────────────────────┐
                      │   ExportNotificationListener (@RabbitListener)│
                      └───────────────┬───────────────────────┬───────┘
                                      │                       │
                                      ▼                       ▼
                      ┌───────────────────────┐   ┌───────────────────────┐
                      │      MailService      │   │NotificationHistorySvc │
                      │  (HTML Email Builder) │   │ (In-Memory Audit Log) │
                      └───────────────┬───────┘   └───────────┬───────────┘
                                      │                       │
                                      ▼                       ▼
                      ┌───────────────────────┐   ┌───────────────────────┐
                      │    Gmail SMTP Relay   │   │ NotificationController│
                      │  smtp.gmail.com:465   │   │  GET /api/v1/notif... │
                      └───────────────┬───────┘   └───────────────────────┘
                                      │
                                      ▼
                      ┌───────────────────────┐
                      │  Recipient Inbox (📬)  │
                      └───────────────────────┘
```

### Key Responsibilities:
1. **AMQP Event Consumption**: Consumes `ExportNotificationMessage` payloads from RabbitMQ using Jackson JSON converter.
2. **HTML Templating**: Generates responsive, styled HTML emails containing customer export metadata (file name, format, record count, execution ID) and a prominent "Download Export" button.
3. **Gmail SMTP Delivery**: Sends emails through `smtp.gmail.com:465` with SSL/TLS encryption and application-specific password authentication.
4. **Audit Trail**: Maintains an in-memory thread-safe circular log of the 50 most recent deliveries, recording timestamp, recipient, export ID, S3 pre-signed URL, status (`SENT`, `FAILED`, `SKIPPED`), and error messages.
5. **Simulation Mode**: If SMTP credentials are omitted, the service runs in simulation mode, logging the pre-signed URL to the console without crashing or failing health checks.

---

## Configuration Properties

All settings can be provided via environment variables:

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | Internal HTTP port (mapped to `8082` in Docker Compose) |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ broker hostname (`rabbitmq` in Docker) |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ username |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ password |
| `RABBITMQ_NOTIFICATION_QUEUE`| `notification-service` | Durable consumer queue name |
| `RABBITMQ_EXCHANGE` | `csv.export.exchange` | DirectExchange name |
| `RABBITMQ_ROUTING_KEY` | `notification.email` | Binding routing key |
| `SMTP_HOST` | `smtp.gmail.com` | SMTP relay server host |
| `SMTP_PORT` | `465` | SMTP port (`465` for SSL, `587` for StartTLS) |
| `SMTP_USERNAME` | `marcosradix@gmail.com` | Authenticated SMTP account |
| `SMTP_PASSWORD` | `(app-password)` | 16-character Google App Password |
| `SMTP_AUTH` | `true` | Enable SMTP authentication |
| `SMTP_SSL` | `true` | Enable SSL (`mail.smtp.ssl.enable`) |
| `SMTP_STARTTLS` | `true` | Enable StartTLS (`mail.smtp.starttls.enable`) |
| `SMTP_STARTTLS_REQUIRED`| `true` | Require StartTLS |
| `MAIL_FROM` | `marcosradix@gmail.com` | Sender email address |
| `MAIL_FROM_NAME` | `Planet Customer Portal`| Sender display name |
| `MAIL_ENABLED` | `true` | Toggle email sending (`false` disables sending) |

---

## REST API Reference

The service is exposed at **`http://localhost:8082`** when running via Docker Compose.

### 1. `GET /api/v1/notifications`
Retrieves recent email notification delivery records (most recent first):

```bash
curl -s http://localhost:8082/api/v1/notifications
```

**Example Response:**
```json
[
  {
    "notificationId": "9e87a972-6a54-4e96-9520-db60290b2810",
    "exportId": "30bf36a5-f912-4919-9d4c-9febcc831a3d",
    "recipientEmail": "user@example.com",
    "filename": "customers_20260926_213029.xlsx",
    "format": "XLSX",
    "recordCount": 4,
    "presignedUrl": "http://localhost:4566/customer-exports/exports/customers_20260926_213029.xlsx?...",
    "status": "SENT",
    "errorMessage": null,
    "processedAt": "2026-09-26T21:30:32.397657927Z"
  }
]
```

### 2. `GET /api/v1/notifications/{exportId}`
Fetches the notification audit record for a specific export UUID:

```bash
curl -s http://localhost:8082/api/v1/notifications/30bf36a5-f912-4919-9d4c-9febcc831a3d
```

### 3. `POST /api/v1/notifications/send-test`
Dispatches an immediate test email to verify SMTP connectivity:

```bash
curl -X POST "http://localhost:8082/api/v1/notifications/send-test?email=your-email@example.com"
```

**Example Response:**
```json
{
  "exportId": "18695ee3-6d57-4e29-ad84-fff766db9b8b",
  "presignedUrl": "http://localhost:4566/customer-exports/exports/customers_test_1790457621025.csv?test=true",
  "status": "SUCCESS",
  "message": "Test notification email sent to your-email@example.com"
}
```

### 4. `GET /actuator/health`
Health checks for liveness and readiness probes:
```bash
curl -s http://localhost:8082/actuator/health
```

---

## Testing

Run the unit test suite:
```bash
mvn test
```
All **8 unit tests** execute with Mockito mocks, verifying listener dispatch, audit storage, and MIME message creation without requiring active network or SMTP connections.
