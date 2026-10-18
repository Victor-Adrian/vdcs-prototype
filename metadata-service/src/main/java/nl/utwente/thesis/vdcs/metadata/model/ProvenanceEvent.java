package nl.utwente.thesis.vdcs.metadata.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PROV-O-inspired record of a read or write, including actor, sources,
 * outcome, result size, digest, and timing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class ProvenanceEvent {

    private String        id;
    private Instant       timestamp;
    private String        operation;
    private String        actor;
    private List<String>  sourcesTouched = new ArrayList<>();
    private String        queryDigest;
    private String        status;
    private Long          resultCount;
    private Long          latencyMs;
    private String        note;

    public ProvenanceEvent() {}

    /** Assigns a fresh UUID + the current timestamp if missing. */
    public void ensureIdentified() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (timestamp == null)          timestamp = Instant.now();
    }

}
