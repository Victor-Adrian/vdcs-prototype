package nl.utwente.thesis.vdcs.metadata.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight HTTP client for metadata and provenance endpoints. It uses
 * the JDK client to keep this shared module independent of Spring.
 */
public class MetadataServiceClient {

    private final String     baseUrl;
    private final HttpClient http;
    private final ObjectMapper json;

    public MetadataServiceClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http    = HttpClient.newBuilder()
                                 .connectTimeout(Duration.ofSeconds(5))
                                 .build();
        this.json    = new ObjectMapper();
    }

    // Sources

    /**
     * Fetches the full source catalogue (without credentials).
     *
     * Used by the semantic mediator's federation engine and by
     * provenance-pushing code that needs source ids and persistent IRIs
     * but no JDBC credentials.
     */
    public SourceCapabilityCatalogue fetchCatalogue() throws IOException, InterruptedException {
        List<SourceDescriptor> sources = getJson("/metadata/sources",
                json.getTypeFactory().constructCollectionType(List.class, SourceDescriptor.class));
        SourceCapabilityCatalogue cat = new SourceCapabilityCatalogue();
        cat.setSources(sources);
        return cat;
    }

    /**
     * Fetches one source descriptor by id (no credentials - those live
     * in each SAC, not in the metadata service).
     */
    public Optional<SourceDescriptor> fetchSource(String sourceId)
            throws IOException, InterruptedException {
        try {
            SourceDescriptor desc = getJson(
                    "/metadata/sources/" + sourceId,
                    json.getTypeFactory().constructType(SourceDescriptor.class));
            return Optional.of(desc);
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Fetches connection details from a source service or adapter.
     * Relational sources return their JDBC endpoint; MongoDB returns its H2
     * projection endpoint.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchConnectionFromSac(String sacOrCsaBaseUrl,
                                                      String path)
            throws IOException, InterruptedException {
        String url = trimTrailingSlash(sacOrCsaBaseUrl) + path;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " → HTTP " + resp.statusCode()
                    + " body=" + resp.body());
        }
        return json.readValue(resp.body(), Map.class);
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // Artefacts

    /**
     * Downloads an ontology or mapping to a temporary file for Ontop.
     *
     * @param artefactId metadata artefact ID
     * @param prefix temporary-file prefix
     * @param suffix temporary-file suffix
     */
    public File fetchArtefactToTempFile(String artefactId, String prefix, String suffix)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/metadata/artefacts/" + artefactId + "/content"))
                .GET().build();
        HttpResponse<byte[]> resp = http.send(req, BodyHandlers.ofByteArray());
        if (resp.statusCode() == 404) {
            throw new NotFoundException("Artefact not found: " + artefactId);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Failed to fetch artefact " + artefactId
                    + ": HTTP " + resp.statusCode());
        }
        File tmp = File.createTempFile(prefix, suffix);
        tmp.deleteOnExit();
        Files.copy(new ByteArrayInputStream(resp.body()),
                tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return tmp;
    }

    // Provenance

    /**
     * Records a provenance event. Fire-and-forget from the caller's
     * point of view - errors are surfaced as exceptions but consumers
     * typically log and move on (a missing audit trail must not break
     * the data path).
     */
    public void recordProvenance(Object event) throws IOException, InterruptedException {
        String body = json.writeValueAsString(event);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/metadata/provenance"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Provenance POST failed: HTTP " + resp.statusCode()
                    + " body=" + resp.body());
        }
    }

    // Internals

    private <T> T getJson(String path, com.fasterxml.jackson.databind.JavaType type)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() == 404) throw new NotFoundException("GET " + path + " → 404");
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("GET " + path + " → HTTP " + resp.statusCode()
                    + " body=" + resp.body());
        }
        return json.readValue(resp.body(), type);
    }

    /** Specific exception so callers can distinguish 404 from other failures. */
    public static class NotFoundException extends IOException {
        public NotFoundException(String msg) { super(msg); }
    }
}
