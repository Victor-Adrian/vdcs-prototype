package nl.utwente.thesis.vdcs.orchestrator.executor;

import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;

import java.util.Set;

/** Applies and compensates canonical updates for one source. */
public interface WriteExecutor {

    /** Source id this executor handles, matching SourceDescriptor.getId(). */
    String sourceId();

    /** Ontology entity types this executor can write. */
    Set<String> supportedEntityTypes();

    /**
     * Applies the update against the source. Must throw if the update
     * fails for any reason - the coordinator translates that into a
     * compensation cascade across previously-completed sub-operations.
     *
     * @return  a handle describing exactly how to undo this write
     */
    CompletedOperation apply(CanonicalUpdate update) throws Exception;

    /**
     * Reverts a previously-applied write. Best-effort: failure here is
     * logged but does not fail the overall compensation cascade.
     */
    void compensate(CompletedOperation handle) throws Exception;
}
