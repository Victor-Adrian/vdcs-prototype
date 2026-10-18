package nl.utwente.thesis.vdcs.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/** Source-independent update expressed with ontology-aligned entity and field names. */
@Getter
public class CanonicalUpdate {

    public enum OperationType { INSERT, UPDATE, DELETE }

    private final OperationType       operation;
    private final String              entityType;  // e.g. "Student", "Grade", "ClubMembership"
    private final Map<String, Object> payload;     // field to value for INSERT / UPDATE
    private final Map<String, Object> filter;      // conditions for UPDATE / DELETE

    @JsonCreator
    public CanonicalUpdate(@JsonProperty("operation")  OperationType operation,
                           @JsonProperty("entityType") String entityType,
                           @JsonProperty("payload")    Map<String, Object> payload,
                           @JsonProperty("filter")     Map<String, Object> filter) {
        this.operation  = operation;
        this.entityType = entityType;
        this.payload    = payload  != null ? Collections.unmodifiableMap(payload) : Collections.emptyMap();
        this.filter     = filter   != null ? Collections.unmodifiableMap(filter)  : Collections.emptyMap();
    }

    @Override
    public String toString() {
        return "CanonicalUpdate{op=" + operation
                + ", entity='" + entityType + "'"
                + ", payload=" + payload + "}";
    }
}
