package nl.utwente.thesis.vdcs.secret.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/** Connection settings and credentials returned for one data source. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class SourceSecret {

    private String sourceId;
    private String engine;        // "postgresql" | "mysql" | "mongodb"

    // Relational
    private String jdbcUrl;
    private String username;
    private String password;
    private String driverClass;

    // MongoDB
    private String mongoUri;
    private String database;

    // SPARQL sources (the publications repository)
    private String sparqlEndpoint;         // query endpoint (reads)
    private String sparqlUpdateEndpoint;   // update endpoint (federated write-back)

    public SourceSecret() {}

}
