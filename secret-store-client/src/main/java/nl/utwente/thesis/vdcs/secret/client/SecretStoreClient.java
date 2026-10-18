package nl.utwente.thesis.vdcs.secret.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/** Fetches source credentials with a service token. */
public class SecretStoreClient {

    private final String       baseUrl;
    private final String       serviceToken;
    private final HttpClient   http;
    private final ObjectMapper json;

    public SecretStoreClient(String baseUrl, String serviceToken) {
        this.baseUrl      = trimTrailingSlash(baseUrl);
        this.serviceToken = serviceToken;
        this.http         = HttpClient.newBuilder()
                                      .connectTimeout(Duration.ofSeconds(5))
                                      .build();
        this.json         = new ObjectMapper();
    }

    /**
     * Fetches the credentials for one source. The store records the
     * access in its audit log. Throws 401 if the token is rejected or
     * 404 the source is unknown.
     */
    public SourceSecret fetch(String sourceId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/secrets/" + sourceId))
                .header("Accept", "application/json")
                .header("X-Service-Token", serviceToken)
                .GET().build();
        HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() == 401) {
            throw new IOException("Secret store rejected service token for source '" + sourceId + "'");
        }
        if (resp.statusCode() == 404) {
            throw new IOException("Secret store has no credentials for source '" + sourceId + "'");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Secret store GET /secrets/" + sourceId
                    + " → HTTP " + resp.statusCode() + " body=" + resp.body());
        }
        return json.readValue(resp.body(), SourceSecret.class);
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
