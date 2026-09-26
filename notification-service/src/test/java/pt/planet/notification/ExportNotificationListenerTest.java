package pt.planet.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pt.planet.notification.dto.ExportNotificationMessage;
import pt.planet.notification.dto.NotificationRecord;
import pt.planet.notification.listener.ExportNotificationListener;
import pt.planet.notification.service.MailService;
import pt.planet.notification.service.NotificationHistoryService;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportNotificationListenerTest {

    @Mock
    private MailService mailService;

    private NotificationHistoryService historyService;
    private ExportNotificationListener listener;

    @BeforeEach
    void setUp() {
        historyService = new NotificationHistoryService();
        listener = new ExportNotificationListener(mailService, historyService);
    }

    @Test
    @DisplayName("Should successfully process export message, send email and record SENT status")
    void testOnExportNotification_Success() throws Exception {
        UUID exportId = UUID.randomUUID();
        ExportNotificationMessage message = new ExportNotificationMessage(
                exportId,
                "customer@example.com",
                "customers_20260926.csv",
                "CSV",
                "customer-exports",
                "exports/customers_20260926.csv",
                "http://localhost:4566/customer-exports/exports/customers_20260926.csv?presigned=true",
                100,
                Instant.now()
        );

        listener.onExportNotification(message);

        verify(mailService).sendExportNotificationEmail(message);

        Optional<NotificationRecord> record = historyService.findByExportId(exportId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo("SENT");
        assertThat(record.get().recipientEmail()).isEqualTo("customer@example.com");
        assertThat(record.get().filename()).isEqualTo("customers_20260926.csv");
    }

    @Test
    @DisplayName("Should skip sending email and record SKIPPED status when email is blank or null")
    void testOnExportNotification_SkipWhenNoEmail() throws Exception {
        UUID exportId = UUID.randomUUID();
        ExportNotificationMessage message = new ExportNotificationMessage(
                exportId,
                null,
                "customers_20260926.csv",
                "CSV",
                "customer-exports",
                "exports/customers_20260926.csv",
                "http://localhost:4566/customer-exports/exports/customers_20260926.csv",
                50,
                Instant.now()
        );

        listener.onExportNotification(message);

        verify(mailService, never()).sendExportNotificationEmail(any());

        Optional<NotificationRecord> record = historyService.findByExportId(exportId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("Should record FAILED status when mail sending throws exception")
    void testOnExportNotification_Failure() throws Exception {
        UUID exportId = UUID.randomUUID();
        ExportNotificationMessage message = new ExportNotificationMessage(
                exportId,
                "invalid@example.com",
                "customers.xlsx",
                "XLSX",
                "customer-exports",
                "exports/customers.xlsx",
                "http://localhost:4566/customer-exports/exports/customers.xlsx",
                20,
                Instant.now()
        );

        doThrow(new RuntimeException("SMTP connection timeout")).when(mailService).sendExportNotificationEmail(message);

        listener.onExportNotification(message);

        Optional<NotificationRecord> record = historyService.findByExportId(exportId);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo("FAILED");
        assertThat(record.get().errorMessage()).isEqualTo("SMTP connection timeout");
    }
}
