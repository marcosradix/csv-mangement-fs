package pt.planet.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import pt.planet.notification.dto.ExportNotificationMessage;
import pt.planet.notification.service.MailService;

import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private MailService mailService;

    @BeforeEach
    void setUp() {
        mailService = new MailService(
                mailSender,
                "noreply@planet.pt",
                "Planet Customer Portal",
                true,
                "testuser"
        );
    }

    @Test
    @DisplayName("Should build and dispatch MIME message with subject, recipient, and pre-signed download link")
    void testSendExportNotificationEmail_Success() throws Exception {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        ExportNotificationMessage message = new ExportNotificationMessage(
                UUID.randomUUID(),
                "recipient@example.com",
                "customers_2026.csv",
                "CSV",
                "customer-exports",
                "exports/customers_2026.csv",
                "http://localhost:4566/customer-exports/exports/customers_2026.csv?token=123",
                42,
                Instant.now()
        );

        mailService.sendExportNotificationEmail(message);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        MimeMessage sent = captor.getValue();
        assertThat(sent).isNotNull();
        assertThat(sent.getSubject()).isEqualTo("Your customer export is ready: customers_2026.csv");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("recipient@example.com");
    }

    @Test
    @DisplayName("Should skip sending when mail is disabled")
    void testSendExportNotificationEmail_Disabled() throws Exception {
        MailService disabledService = new MailService(
                mailSender,
                "noreply@planet.pt",
                "Planet",
                false,
                "testuser"
        );

        ExportNotificationMessage message = new ExportNotificationMessage(
                UUID.randomUUID(),
                "recipient@example.com",
                "customers.csv",
                "CSV",
                "bucket",
                "key",
                "http://url",
                10,
                Instant.now()
        );

        disabledService.sendExportNotificationEmail(message);

        verifyNoInteractions(mailSender);
    }
}
