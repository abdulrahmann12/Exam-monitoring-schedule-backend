package schedule.example.schedule.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class MailService {

	private final JavaMailSender mailSender;
	private final String fromAddress;

	public MailService(
		JavaMailSender mailSender,
		@Value("${spring.mail.username}") String fromAddress
	) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	public void sendHtml(String to, String subject, String htmlBody) {
		sendHtmlWithPdf(to, subject, htmlBody, null, null);
	}

	public void sendHtmlWithPdf(String to, String subject, String htmlBody, String filename, byte[] pdf) {
		try {
			boolean multipart = pdf != null && pdf.length > 0 && filename != null && !filename.isBlank();
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
			helper.setFrom(fromAddress);
			helper.setTo(to);
			helper.setSubject(subject);
			helper.setText(htmlBody, true);
			if (multipart) {
				helper.addAttachment(filename, new ByteArrayResource(pdf), "application/pdf");
			}
			mailSender.send(message);
		} catch (MessagingException ex) {
			throw new IllegalStateException("Failed to send email to " + to, ex);
		}
	}
}
