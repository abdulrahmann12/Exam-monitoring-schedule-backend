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
import schedule.example.schedule.config.Messages;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;

@Service
public class MailService {



	private final RestTemplate restTemplate;
	private final String apiUrl;
	private final String apiKey;
	private final String senderEmail;
	private final String senderName;

	public MailService(
		@Value("${app.brevo.api-key}") String apiKey,
		@Value("${app.brevo.sender-email}") String senderEmail,
		@Value("${app.brevo.sender-name:Uni-Guard Schedules}") String senderName,
		@Value("${app.brevo.api-url:https://api.brevo.com/v3/smtp/email}") String apiUrl
	) {
		this.apiUrl = apiUrl;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(10_000);
		factory.setReadTimeout(20_000);
		this.restTemplate = new RestTemplate(factory);
		this.apiKey = apiKey;
		this.senderEmail = senderEmail;
		this.senderName = senderName;
	}

	/** False when BREVO_API_KEY or BREVO_SENDER_EMAIL is not set (e.g. on-premise installs without email). */
	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank() && senderEmail != null && !senderEmail.isBlank();
	}

	/** @return the provider's message id (may be null) */
	public String sendHtml(String to, String subject, String htmlBody) {
		return sendHtmlWithPdf(to, subject, htmlBody, null, null);
	}

	/**
	 * Hands one email to Brevo.
	 *
	 * @return the provider's message id (may be null)
	 * @throws MailDeliveryException with {@code retryable} set for temporary problems
	 */
	public String sendHtmlWithPdf(String to, String subject, String htmlBody, String filename, byte[] pdf) {
		if (!isConfigured()) {
			throw new IllegalStateException(Messages.MAIL_NOT_CONFIGURED);
		}
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

		ResponseEntity<String> response;
		try {
			response = restTemplate.exchange(
				apiUrl,
				HttpMethod.POST,
				new HttpEntity<>(payload, headers),
				String.class
			);
		} catch (HttpStatusCodeException ex) {
			int status = ex.getStatusCode().value();
			// 429 (too many) and 5xx (provider trouble) go away by themselves; other 4xx do not.
			boolean retryable = status == 429 || status >= 500;
			throw new MailDeliveryException("Brevo answered " + status + ": " + shorten(ex.getResponseBodyAsString()), retryable, ex);
		} catch (ResourceAccessException ex) {
			throw new MailDeliveryException("Could not reach Brevo: " + ex.getMessage(), true, ex);
		} catch (RuntimeException ex) {
			throw new MailDeliveryException("Could not send via Brevo: " + ex.getMessage(), true, ex);
		}
		if (!response.getStatusCode().is2xxSuccessful()) {
			throw new MailDeliveryException("Brevo answered " + response.getStatusCode().value(), response.getStatusCode().is5xxServerError(), null);
		}
		return messageId(response.getBody());
	}

	private static String messageId(String body) {
		if (body == null) {
			return null;
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"messageId\"\\s*:\\s*\"([^\"]{1,190})\"").matcher(body);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String shorten(String text) {
		if (text == null) {
			return "";
		}
		String oneLine = text.replaceAll("\\s+", " ").trim();
		return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
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
