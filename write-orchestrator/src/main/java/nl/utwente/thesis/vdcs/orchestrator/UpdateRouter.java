package nl.utwente.thesis.vdcs.orchestrator;

import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import nl.utwente.thesis.vdcs.metadata.client.SourceDescriptor;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;

import java.util.List;

/** Resolves the writable source owners for a canonical entity. */
public class UpdateRouter {

    private final SourceCapabilityCatalogue catalogue;

    public UpdateRouter(SourceCapabilityCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * Returns all sources that are authoritative for the entity type of
     * the given update and declare write capability.
     */
    public List<SourceDescriptor> resolveTargets(CanonicalUpdate update) {
        return catalogue.findWritableByEntity(update.getEntityType());
    }
}
