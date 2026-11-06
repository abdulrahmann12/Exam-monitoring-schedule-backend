package schedule.example.schedule.security.sso;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** The two calls to Google / Microsoft: redeem the code, and read their public signing keys. */
@Component
public class OidcHttpClient {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(TIMEOUT)
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	public OidcHttpClient(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Map<String, Object> postForm(String uri, Map<String, String> form) {
		String body = form.entrySet().stream()
			.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
			.collect(Collectors.joining("&"));
		HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
			.timeout(TIMEOUT)
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return send(request);
	}

	public Map<String, Object> getJson(String uri) {
		return send(HttpRequest.newBuilder(URI.create(uri)).timeout(TIMEOUT).header("Accept", "application/json").GET().build());
	}

	private Map<String, Object> send(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			Map<String, Object> json = response.body() == null || response.body().isBlank() ? Map.of() : objectMapper.readValue(response.body(), JSON_MAP);
			if (response.statusCode() / 100 != 2) {
				// Only the provider's error code, never tokens.
				throw new SsoException(SsoException.FAILED, "Provider answered " + response.statusCode() + " (" + json.get("error") + ") for " + request.uri().getHost());
			}
			return json;
		} catch (IOException ex) {
			throw new SsoException(SsoException.UNAVAILABLE, "Could not reach " + request.uri().getHost() + ": " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SsoException(SsoException.UNAVAILABLE, "Interrupted while calling " + request.uri().getHost(), ex);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}
}
