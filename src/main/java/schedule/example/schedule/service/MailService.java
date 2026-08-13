package schedule.example.schedule.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;

@Service
public class MailService {

	private static final String BREVO_SMTP_EMAIL_URL = "https://api.brevo.com/v3/smtp/email";

	private final RestTemplate restTemplate;
	private final String apiKey;
	private final String senderEmail;
	private final String senderName;

	public MailService(
		@Value("${app.brevo.api-key}") String apiKey,
		@Value("${app.brevo.sender-email}") String senderEmail,
		@Value("${app.brevo.sender-name:Uni-Guard Schedules}") String senderName
	) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(10_000);
		factory.setReadTimeout(20_000);
		this.restTemplate = new RestTemplate(factory);
		this.apiKey = apiKey;
		this.senderEmail = senderEmail;
		this.senderName = senderName;
	}

	public void sendHtml(String to, String subject, String htmlBody) {
		sendHtmlWithPdf(to, subject, htmlBody, null, null);
	}

	public void sendHtmlWithPdf(String to, String subject, String htmlBody, String filename, byte[] pdf) {
		List<BrevoAttachment> attachments = null;
		if (pdf != null && pdf.length > 0 && filename != null && !filename.isBlank()) {
			attachments = List.of(new BrevoAttachment(Base64.getEncoder().encodeToString(pdf), filename));
		}

		BrevoEmailRequest payload = new BrevoEmailRequest(
			new BrevoSender(senderName, senderEmail),
			List.of(new BrevoRecipient(to)),
			subject,
			htmlBody,
			attachments
		);

		HttpHeaders headers = new HttpHeaders();
		headers.set("api-key", apiKey);
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(List.of(MediaType.APPLICATION_JSON));

		try {
			ResponseEntity<String> response = restTemplate.exchange(
				BREVO_SMTP_EMAIL_URL,
				HttpMethod.POST,
				new HttpEntity<>(payload, headers),
				String.class
			);
			if (!response.getStatusCode().is2xxSuccessful()) {
				throw new IllegalStateException("Brevo rejected email to " + to + " with status " + response.getStatusCode());
			}
		} catch (HttpStatusCodeException ex) {
			throw new IllegalStateException(
				"Failed to send email to " + to + " via Brevo: " + ex.getStatusCode() + " " + ex.getResponseBodyAsString(),
				ex
			);
		} catch (RuntimeException ex) {
			throw new IllegalStateException("Failed to send email to " + to + " via Brevo", ex);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record BrevoEmailRequest(
		BrevoSender sender,
		List<BrevoRecipient> to,
		String subject,
		String htmlContent,
		List<BrevoAttachment> attachment
	) {
	}

	private record BrevoSender(String name, String email) {
	}

	private record BrevoRecipient(String email) {
	}

	private record BrevoAttachment(String content, String name) {
	}
}
