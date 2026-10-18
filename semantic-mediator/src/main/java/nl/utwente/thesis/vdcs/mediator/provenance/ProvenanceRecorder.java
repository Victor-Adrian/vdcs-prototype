package nl.utwente.thesis.vdcs.mediator.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.utwente.thesis.vdcs.metadata.client.MetadataServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records query digests, result counts, timing, and actors on a best-effort basis. */
@Component
public class ProvenanceRecorder {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceRecorder.class);

    private final MetadataServiceClient client;
    private final ObjectMapper          mapper = new ObjectMapper();

    public ProvenanceRecorder(MetadataServiceClient client) {
        this.client = client;
    }

    /**
     * Records a SPARQL read.
     *
     * @param sourcesTouched source IDs used by the query
     * @param sparql original query, used for its digest
     * @param status SUCCESS or FAILURE
     * @param resultCount binding count, or null on failure
     * @param latencyMs elapsed time
     * @param note optional detail such as a failure reason
     */
    public void recordRead(Set<String> sourcesTouched, String sparql, String status,
                           Long resultCount, long latencyMs, String note, String actor) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp",     Instant.now().toString());
        event.put("operation",     "READ");
        event.put("actor",         actor == null || actor.isBlank() ? "anonymous" : actor);
        event.put("sourcesTouched", List.copyOf(sourcesTouched));
        event.put("queryDigest",   digest(sparql));
        event.put("status",        status);
        event.put("resultCount",   resultCount);
        event.put("latencyMs",     latencyMs);
        event.put("note",          note);
        send(event);
    }

    private void send(Map<String, Object> event) {
        try {
            client.recordProvenance(event);
        } catch (Exception e) {
            log.warn("Failed to record provenance event (best-effort): {}", e.getMessage());
        }
    }

    /** Counts result bindings, returning zero when the response cannot be parsed. */
    public long countBindings(String sparqlResultsJson) {
        try {
            JsonNode root = mapper.readTree(sparqlResultsJson);
            JsonNode bindings = root.path("results").path("bindings");
            return bindings.isArray() ? bindings.size() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Returns a lower-case SHA-256 digest without retaining query content. */
    public static String digest(String query) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }

}
