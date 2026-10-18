package nl.utwente.thesis.vdcs.metadata.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

/**
 * Source identity, capabilities, connection endpoints, and FAIR metadata
 * returned by the metadata service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class SourceDescriptor {

    // Identity (FAIR: Findable)
    private String id;
    private String persistentId;
    private String title;
    private String description;

    // Connectivity (FAIR: Accessible)
    private String type;                  // "relational" | "document"
    private String engine;                // "postgresql" | "mysql" | "mongodb"
    private String adapter;               // id of canonical adapter, if required

    /** Base URL used for source writes and connection details. */
    private String sacUrl;

    /** Optional adapter URL for sources with a separate canonical projection. */
    private String csaUrl;

    // Database credentials are owned by the source access services.

    // Capability & content (FAIR: Findable)
    private List<String> capabilities = new ArrayList<>(); // "read", "write"
    private List<String> entities     = new ArrayList<>(); // ontology class names
    private List<String> produces     = new ArrayList<>(); // class + predicate local names

    // Interoperability (FAIR: Interoperable)
    private String conformsTo;

    // Reuse (FAIR: Reusable)
    private String license;
    private String licenseUrl;
    private String owner;
    private String created;
    private String modified;

    public SourceDescriptor() {}

    // Getters / setters

    /** Convenience: true when this source requires a canonical adapter. */
    public boolean requiresAdapter() {
        return adapter != null && !adapter.isBlank();
    }

    @Override
    public String toString() {
        return "SourceDescriptor{id='" + id + "', engine='" + engine + "'}";
    }
}
