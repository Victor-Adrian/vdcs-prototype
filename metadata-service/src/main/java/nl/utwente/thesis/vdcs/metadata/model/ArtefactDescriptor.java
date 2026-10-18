package nl.utwente.thesis.vdcs.metadata.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/** Metadata for a governed ontology or mapping artefact. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class ArtefactDescriptor {

    private String id;
    private String persistentId;
    private String title;
    private String description;
    private String kind;           // "ontology" | "mapping"
    private String format;         // MIME type
    private String version;        // e.g. "1.0.0"
    private String classpath;      // resource path inside the JAR
    private String license;
    private String licenseUrl;
    private String owner;
    private String created;
    private String modified;
    private String conformsTo;     // for mappings: which ontology version
    private String appliesTo;      // for mappings: which source id

    public ArtefactDescriptor() {}

}
