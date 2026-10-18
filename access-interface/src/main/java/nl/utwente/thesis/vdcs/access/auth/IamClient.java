package nl.utwente.thesis.vdcs.access.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves bearer tokens through IAM and reports access decisions to
 * its audit log.
 */
@Component
public class IamClient {

    private static final Logger log = LoggerFactory.getLogger(IamClient.class);

    private final String       iamUrl;
    private final HttpClient   http;
    private final ObjectMapper json = new ObjectMapper();

    public IamClient(@Value("${vdcs.iam.url:http://localhost:8087}") String iamUrl) {
        this.iamUrl = iamUrl.endsWith("/") ? iamUrl.substring(0, iamUrl.length() - 1) : iamUrl;
        this.http   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Resolve a bearer token to its principal, or empty if unknown/invalid. */
    public Optional<Principal> authenticate(String token) {
        try {
            String body = json.writeValueAsString(Map.of("token", token == null ? "" : token));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(iamUrl + "/iam/authenticate"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
            if (resp.statusCode() == 401) return Optional.empty();
            if (resp.statusCode() / 100 != 2) {
                log.warn("IAM authenticate returned HTTP {}", resp.statusCode());
                return Optional.empty();
            }
            return Optional.of(json.readValue(resp.body(), Principal.class));
        } catch (Exception e) {
            // Fail closed: if IAM is unreachable, deny.
            log.warn("IAM service unreachable ({}). Denying request.", e.getMessage());
            return Optional.empty();
        }
    }

    /** Best-effort report of an access decision to IAM's audit log. */
    public void reportAccess(Map<String, Object> event) {
        try {
            String body = json.writeValueAsString(event);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(iamUrl + "/iam/audit"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.send(req, BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("IAM audit report failed (best-effort): {}", e.getMessage());
        }
    }
}
