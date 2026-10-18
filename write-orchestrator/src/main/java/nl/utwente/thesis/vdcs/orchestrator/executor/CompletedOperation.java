package nl.utwente.thesis.vdcs.orchestrator.executor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;

import java.util.Map;

/** Snapshot and source identity needed to compensate a successful write. */
@Getter
public final class CompletedOperation {

    private final String          sourceId;
    private final CanonicalUpdate originalUpdate;
    private final Map<String, Object> snapshot;

    @JsonCreator
    public CompletedOperation(@JsonProperty("sourceId")       String sourceId,
                              @JsonProperty("originalUpdate") CanonicalUpdate originalUpdate,
                              @JsonProperty("snapshot")       Map<String, Object> snapshot) {
        this.sourceId       = sourceId;
        this.originalUpdate = originalUpdate;
        this.snapshot       = Map.copyOf(snapshot);
    }

    @Override
    public String toString() {
        return "CompletedOperation{source=" + sourceId
                + ", op=" + originalUpdate.getOperation()
                + ", entity=" + originalUpdate.getEntityType()
                + ", snapshot=" + snapshot + "}";
    }
}
