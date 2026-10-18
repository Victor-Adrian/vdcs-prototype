package nl.utwente.thesis.vdcs.secret.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

/** Source credentials and the service tokens allowed to retrieve them. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class SecretEntry {

    private String sourceId;
    private String engine;

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

    private List<String> allowedTokens = new ArrayList<>();

    public SecretEntry() {}

}
