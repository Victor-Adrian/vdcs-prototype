package nl.utwente.thesis.vdcs.orchestrator.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/** Forwards apply and compensation requests to one source access service. */
public class RemoteSacWriteExecutor implements WriteExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemoteSacWriteExecutor.class);

    private final String        sourceId;
    private final Set<String>   supportedEntityTypes;
    private final String        sacBaseUrl;
    private final String        writePath;       // e.g. "/sac/write" or "/csa/write"
    private final String        compensatePath;  // e.g. "/sac/compensate"
    private final HttpClient    http;
    private final ObjectMapper  json;

    public RemoteSacWriteExecutor(String sourceId,
                                  Set<String> supportedEntityTypes,
                                  String sacBaseUrl) {
        this(sourceId, supportedEntityTypes, sacBaseUrl, "/sac/write", "/sac/compensate");
    }

    public RemoteSacWriteExecutor(String sourceId,
                                  Set<String> supportedEntityTypes,
                                  String sacBaseUrl,
                                  String writePath,
                                  String compensatePath) {
        this.sourceId             = sourceId;
        this.supportedEntityTypes = Set.copyOf(supportedEntityTypes);
        this.sacBaseUrl           = trimTrailingSlash(sacBaseUrl);
        this.writePath            = writePath;
        this.compensatePath       = compensatePath;
        this.http                 = HttpClient.newBuilder()
                                              .connectTimeout(Duration.ofSeconds(5))
                                              .build();
        this.json                 = new ObjectMapper();
    }

    @Override public String      sourceId()             { return sourceId; }
    @Override public Set<String> supportedEntityTypes() { return supportedEntityTypes; }

    @Override
    public CompletedOperation apply(CanonicalUpdate update) throws Exception {
        String body = json.writeValueAsString(update);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(sacBaseUrl + writePath))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException(
                    "Remote SAC " + sourceId + " rejected write: HTTP " + resp.statusCode()
                  + " body=" + resp.body());
        }
        CompletedOperation handle = json.readValue(resp.body(), CompletedOperation.class);
        log.debug("Wrote via remote SAC {}: {}", sourceId, handle);
        return handle;
    }

    @Override
    public void compensate(CompletedOperation handle) throws Exception {
        String body = json.writeValueAsString(handle);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(sacBaseUrl + compensatePath))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            // Compensation failures are best-effort - log and continue. The
            // caller (CompensationManager) already treats compensation as
            // best-effort under saga semantics.
            log.warn("Remote SAC {} compensation returned HTTP {} — body={}",
                    sourceId, resp.statusCode(), resp.body());
        } else {
            log.info("Compensated [{}] via remote SAC", sourceId);
        }
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
