package nl.utwente.thesis.vdcs.orchestrator.service.provenance;

import nl.utwente.thesis.vdcs.metadata.client.MetadataServiceClient;
import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import nl.utwente.thesis.vdcs.metadata.client.SourceDescriptor;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Sends write provenance to the metadata service without blocking the write path. */
@Component
public class WriteProvenanceRecorder {

    private static final Logger log = LoggerFactory.getLogger(WriteProvenanceRecorder.class);

    private final MetadataServiceClient     client;
    private final SourceCapabilityCatalogue catalogue;

    public WriteProvenanceRecorder(MetadataServiceClient client,
                                   SourceCapabilityCatalogue catalogue) {
        this.client    = client;
        this.catalogue = catalogue;
    }

    public void recordWrite(List<CanonicalUpdate> updates, String requestBody,
                            String status, long latencyMs, String note, String actor) {
        Set<String> sources = new TreeSet<>();
        for (CanonicalUpdate u : updates) {
            for (SourceDescriptor s : catalogue.findWritableByEntity(u.getEntityType())) {
                sources.add(s.getId());
            }
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp",      Instant.now().toString());
        event.put("operation",      "WRITE");
        event.put("actor",          actor == null || actor.isBlank() ? "anonymous" : actor);
        event.put("sourcesTouched", new ArrayList<>(sources));
        event.put("queryDigest",    digest(requestBody));
        event.put("status",         status);
        event.put("resultCount",    (long) updates.size());
        event.put("latencyMs",      latencyMs);
        event.put("note",           note);

        try {
            client.recordProvenance(event);
        } catch (Exception e) {
            log.warn("Failed to record write provenance (best-effort): {}", e.getMessage());
        }
    }

    private static String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }
}
