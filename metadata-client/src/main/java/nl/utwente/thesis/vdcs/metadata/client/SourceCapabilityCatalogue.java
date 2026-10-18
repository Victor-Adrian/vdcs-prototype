package nl.utwente.thesis.vdcs.metadata.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Source catalogue returned by the metadata service, with routing lookups. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class SourceCapabilityCatalogue {

    @JsonProperty("sources")
    private List<SourceDescriptor> sources = new ArrayList<>();

    public SourceCapabilityCatalogue() {}

    public Optional<SourceDescriptor> findById(String id) {
        return sources.stream()
                .filter(s -> id.equals(s.getId()))
                .findFirst();
    }

    /** All sources authoritative for an ontology entity type. */
    public List<SourceDescriptor> findByEntity(String entityType) {
        return sources.stream()
                .filter(s -> s.getEntities().contains(entityType))
                .collect(Collectors.toList());
    }

    /** All sources that declare a capability ("read" or "write"). */
    public List<SourceDescriptor> findByCapability(String capability) {
        return sources.stream()
                .filter(s -> s.getCapabilities().contains(capability))
                .collect(Collectors.toList());
    }

    /** Sources writable for a given entity type. Used by UpdateRouter. */
    public List<SourceDescriptor> findWritableByEntity(String entityType) {
        return findByEntity(entityType).stream()
                .filter(s -> s.getCapabilities().contains("write"))
                .collect(Collectors.toList());
    }
}
