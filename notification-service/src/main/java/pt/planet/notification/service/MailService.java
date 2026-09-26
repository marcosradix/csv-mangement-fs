package pt.planet.notification.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import pt.planet.notification.dto.ExportNotificationMessage;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;
    private final boolean mailEnabled;
    private final String smtpUsername;

    @Autowired
    public MailService(
            @Autowired(required = false) JavaMailSender mailSender,
            @Value("${app.mail.from:noreply@planet.pt}") String fromAddress,
            @Value("${app.mail.from-name:Planet Customer Portal}") String fromName,
            @Value("${app.mail.enabled:true}") boolean mailEnabled,
            @Value("${spring.mail.username:}") String smtpUsername
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.mailEnabled = mailEnabled;
        this.smtpUsername = smtpUsername;
    }

    public void sendExportNotificationEmail(ExportNotificationMessage message) throws Exception {
        String recipient = message.email();
        if (recipient == null || recipient.isBlank()) {
            log.warn("No recipient email specified in export message {}. Skipping email delivery.", message.exportId());
            return;
        }

        if (!mailEnabled || smtpUsername == null || smtpUsername.isBlank()) {
            log.info("[SIMULATION] Mail delivery in simulation mode (no SMTP credentials). Notification for export {} to '{}'. Pre-signed URL: {}",
                    message.exportId(), recipient, message.presignedUrl());
            return;
        }

        if (mailSender == null) {
            log.warn("JavaMailSender is not configured. Cannot deliver email to {}", recipient);
            throw new IllegalStateException("JavaMailSender is not configured");
        }

        log.info("Preparing export ready email for '{}' (Export ID: {})", recipient, message.exportId());

        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());

        helper.setFrom(fromAddress, fromName);
        helper.setTo(recipient);
        helper.setSubject("Your customer export is ready: " + message.filename());

        String htmlContent = buildHtmlContent(message);
        String textContent = buildPlainTextContent(message);

        helper.setText(textContent, htmlContent);

        mailSender.send(mimeMessage);
        log.info("Successfully sent export download email to '{}' for file '{}'", recipient, message.filename());
    }

    private String buildHtmlContent(ExportNotificationMessage message) {
        String template = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f4f7f6; margin: 0; padding: 20px; color: #333333; }
                    .card { max-width: 600px; margin: 0 auto; background: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 15px rgba(0, 0, 0, 0.08); }
                    .header { background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%); color: #ffffff; padding: 30px; text-align: center; }
                    .header h1 { margin: 0; font-size: 24px; font-weight: 600; letter-spacing: -0.5px; }
                    .body { padding: 30px; }
                    .info-table { width: 100%; border-collapse: collapse; margin: 20px 0; }
                    .info-table td { padding: 10px 12px; border-bottom: 1px solid #edf2f7; font-size: 14px; }
                    .info-table td.label { font-weight: 600; color: #4a5568; width: 35%; }
                    .info-table td.value { color: #1a202c; }
                    .btn-container { text-align: center; margin: 30px 0; }
                    .btn { display: inline-block; background: #2563eb; color: #ffffff !important; text-decoration: none; padding: 14px 28px; font-size: 16px; font-weight: 600; border-radius: 8px; box-shadow: 0 4px 6px -1px rgba(37, 99, 235, 0.2); }
                    .btn:hover { background: #1d4ed8; }
                    .url-box { background: #f8fafc; border: 1px dashed #cbd5e1; border-radius: 6px; padding: 12px; word-break: break-all; font-family: monospace; font-size: 12px; color: #64748b; margin-top: 15px; }
                    .footer { background: #f8fafc; padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #e2e8f0; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="header">
                        <h1>Your Export File is Ready</h1>
                    </div>
                    <div class="body">
                        <p>Hello,</p>
                        <p>Your requested customer data export has finished processing and is ready for download.</p>
                        
                        <table class="info-table">
                            <tr>
                                <td class="label">Filename:</td>
                                <td class="value"><strong>{filename}</strong></td>
                            </tr>
                            <tr>
                                <td class="label">Format:</td>
                                <td class="value">{format}</td>
                            </tr>
                            <tr>
                                <td class="label">Total Records:</td>
                                <td class="value">{recordCount}</td>
                            </tr>
                            <tr>
                                <td class="label">Export ID:</td>
                                <td class="value">{exportId}</td>
                            </tr>
                        </table>

                        <div class="btn-container">
                            <a href="{presignedUrl}" class="btn" target="_blank">Download Export File</a>
                        </div>

                        <p style="font-size: 13px; color: #64748b;">
                            If the button above does not work, copy and paste this link into your browser:
                        </p>
                        <div class="url-box">
                            {presignedUrlEscaped}
                        </div>
                    </div>
                    <div class="footer">
                        <p>This is an automated notification from Planet Customer Portal.</p>
                        <p>The download link is temporary and will expire automatically.</p>
                    </div>
                </div>
            </body>
            </html>
            """;

        String url = message.presignedUrl() != null ? message.presignedUrl() : "";
        return template
                .replace("{filename}", escapeHtml(message.filename()))
                .replace("{format}", escapeHtml(message.format()))
                .replace("{recordCount}", String.valueOf(message.recordCount()))
                .replace("{exportId}", message.exportId() != null ? message.exportId().toString() : "")
                .replace("{presignedUrl}", url)
                .replace("{presignedUrlEscaped}", escapeHtml(url));
    }

    private String buildPlainTextContent(ExportNotificationMessage message) {
        String template = """
            Hello,

            Your customer data export has finished processing and is ready for download.

            File Details:
            - Filename: {filename}
            - Format: {format}
            - Total Records: {recordCount}
            - Export ID: {exportId}

            Download Link (Pre-signed URL):
            {presignedUrl}

            Please note that this link is temporary and will expire automatically.

            Best regards,
            Planet Customer Portal
            """;

        return template
                .replace("{filename}", message.filename() != null ? message.filename() : "")
                .replace("{format}", message.format() != null ? message.format() : "")
                .replace("{recordCount}", String.valueOf(message.recordCount()))
                .replace("{exportId}", message.exportId() != null ? message.exportId().toString() : "")
                .replace("{presignedUrl}", message.presignedUrl() != null ? message.presignedUrl() : "");
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
