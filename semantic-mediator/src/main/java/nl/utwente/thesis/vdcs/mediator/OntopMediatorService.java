package nl.utwente.thesis.vdcs.mediator;

import it.unibz.inf.ontop.injection.OntopSQLOWLAPIConfiguration;
import it.unibz.inf.ontop.rdf4j.repository.OntopRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import nl.utwente.thesis.vdcs.mediator.federation.FederatedQueryEngine;
import nl.utwente.thesis.vdcs.mediator.federation.PredicateSourceRegistry;
import nl.utwente.thesis.vdcs.mediator.provenance.ProvenanceRecorder;
import nl.utwente.thesis.vdcs.metadata.client.MetadataServiceClient;
import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import nl.utwente.thesis.vdcs.metadata.client.SourceDescriptor;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.resultio.QueryResultIO;
import org.eclipse.rdf4j.query.resultio.TupleQueryResultFormat;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds one repository per source from catalogue artefacts and connection
 * details returned by each source access service, then executes local or
 * federated SPARQL queries over them.
 */
@Service
public class OntopMediatorService {

    private static final Logger log = LoggerFactory.getLogger(OntopMediatorService.class);

    /** Source IDs (must match catalogue.yml). */
    private static final String SRC_PG    = "student-registry";
    private static final String SRC_MYSQL = "academic-records";
    private static final String SRC_MONGO = "student-activities";
    private static final String SRC_PUBS  = "research-publications";

    private static final String SRC_LOANS = "library-loans";

    /** Artefact IDs (must match artefacts.yml in the metadata service). */
    private static final String ART_ONTOLOGY      = "ontology";
    private static final String ART_MAPPING_PG    = "mapping-pg";
    private static final String ART_MAPPING_MYSQL = "mapping-mysql";
    private static final String ART_MAPPING_MONGO = "mapping-mongo";
    private static final String ART_MAPPING_PUBS  = "mapping-publications";

    private static final String ART_MAPPING_LOANS = "mapping-loans";

    private final SourceCapabilityCatalogue catalogue;
    private final MetadataServiceClient     metadata;
    private final ProvenanceRecorder        provenance;

    // Ontop handles relational sources; RDF4J connects to the graph source.
    private OntopRepository pgRepo;
    private OntopRepository mysqlRepo;
    private OntopRepository mongoRepo;
    private Repository      pubsRepo;

    private OntopRepository loansRepo;

    // Federation plumbing
    private PredicateSourceRegistry registry;
    private FederatedQueryEngine    federation;

    public OntopMediatorService(SourceCapabilityCatalogue catalogue,
                                MetadataServiceClient     metadata,
                                ProvenanceRecorder        provenance) {
        this.catalogue  = catalogue;
        this.metadata   = metadata;
        this.provenance = provenance;
    }

    // Lifecycle

    @PostConstruct
    public void init() throws Exception {
        log.info("Initialising Ontop repositories…");

        // fetch ontology + mapping artefacts to temp files
        File ontologyFile     = metadata.fetchArtefactToTempFile(ART_ONTOLOGY,      "vdcs-ontology-",        ".ttl");
        File pgMappingFile    = metadata.fetchArtefactToTempFile(ART_MAPPING_PG,    "vdcs-mapping-pg-",      ".obda");
        File mysqlMappingFile = metadata.fetchArtefactToTempFile(ART_MAPPING_MYSQL, "vdcs-mapping-mysql-",   ".obda");
        File mongoMappingFile = metadata.fetchArtefactToTempFile(ART_MAPPING_MONGO, "vdcs-mapping-mongo-",   ".obda");
        // The federation engine applies this CONSTRUCT mapping at query time.
        File pubsMappingFile  = metadata.fetchArtefactToTempFile(ART_MAPPING_PUBS,  "vdcs-mapping-pubs-",    ".rq");
        String pubsMapping    = Files.readString(pubsMappingFile.toPath(), StandardCharsets.UTF_8);
        log.info("Fetched ontology + 4 mappings from metadata service");

        // ask each SAC for the connection the mediator should use
        Map<String, Object> pgConn    = readConnection(SRC_PG);
        Map<String, Object> mysqlConn = readConnection(SRC_MYSQL);
        Map<String, Object> mongoConn = readConnection(SRC_MONGO);
        Map<String, Object> pubsConn  = readConnection(SRC_PUBS);

        // build one Ontop repo per source
        pgRepo = buildRepository("student-registry (PostgreSQL)",
                ontologyFile, pgMappingFile, pgConn);
        pgRepo.init();
        log.info("Ontop/PostgreSQL repository ready ({}).", pgConn.get("jdbcUrl"));

        mysqlRepo = buildRepository("academic-records (MySQL)",
                ontologyFile, mysqlMappingFile, mysqlConn);
        mysqlRepo.init();
        log.info("Ontop/MySQL repository ready ({}).", mysqlConn.get("jdbcUrl"));

        mongoRepo = buildRepository("student-activities (MongoDB via CSA's H2)",
                ontologyFile, mongoMappingFile, mongoConn);
        mongoRepo.init();
        log.info("Ontop/MongoDB repository ready (canonical H2 endpoint = {}).",
                mongoConn.get("jdbcUrl"));

        // Connect to the RDF source directly and verify it with ASK.
        String pubsEndpoint = (String) pubsConn.get("sparqlEndpoint");
        pubsRepo = new SPARQLRepository(pubsEndpoint);
        pubsRepo.init();
        try (RepositoryConnection c = pubsRepo.getConnection()) {
            c.prepareBooleanQuery("ASK {}").evaluate();
        }
        log.info("Publications repository ready (direct SPARQL endpoint = {}).", pubsEndpoint);

        // Library Loans uses an embedded, read-only H2 source.
        File loansMappingFile = metadata.fetchArtefactToTempFile(ART_MAPPING_LOANS, "vdcs-mapping-loans-", ".obda");
        Map<String, Object> loansConn = readConnection(SRC_LOANS);
        loansRepo = buildRepository("library-loans (H2)", ontologyFile, loansMappingFile, loansConn);
        loansRepo.init();
        log.info("Ontop/Library-Loans repository ready ({}).", loansConn.get("jdbcUrl"));

        // Start federation with the graph source's query-time CONSTRUCT mapping.
        this.registry   = new PredicateSourceRegistry(catalogue);
        this.federation = new FederatedQueryEngine(reposBySourceId(), registry,
                Map.of(SRC_PUBS, pubsMapping));
        log.info("Federation engine ready.");
    }

    @PreDestroy
    public void shutdown() {
        if (pgRepo    != null) pgRepo.shutDown();
        if (mysqlRepo != null) mysqlRepo.shutDown();
        if (mongoRepo != null) mongoRepo.shutDown();
        if (pubsRepo  != null) pubsRepo.shutDown();

        if (loansRepo != null) loansRepo.shutDown();
    }

    // Public API

    public String executeSparql(String sparql) {
        return executeSparql(sparql, "anonymous");
    }

    /** Executes SPARQL and attributes provenance to the supplied actor. */
    public String executeSparql(String sparql, String actor) {
        Set<String> touched = federation.sourcesFor(sparql);
        log.debug("SPARQL actor={} touches sources={} query={}", actor, touched, sparql);

        long started = System.currentTimeMillis();
        String result;
        try {
            // Query-time mappings must run through federation before matching.
            boolean queryTimeMapped =
                    touched.stream().anyMatch(federation::isQueryTimeMapped);
            if (touched.size() > 1 || queryTimeMapped) {
                result = federation.execute(sparql);
            } else {
                String src = touched.isEmpty() ? SRC_PG : touched.iterator().next();
                result = switch (src) {
                    case SRC_PG    -> executeOn(pgRepo, sparql);
                    case SRC_MYSQL -> executeOn(mysqlRepo, sparql);
                    case SRC_MONGO -> executeOn(mongoRepo, sparql);
                    case SRC_LOANS -> executeOn(loansRepo, sparql);
                    default -> throw new IllegalStateException(
                            "Unknown source id from registry: " + src);
                };
            }
        } catch (RuntimeException re) {
            provenance.recordRead(touched, sparql, "FAILURE",
                    null, System.currentTimeMillis() - started, re.getMessage(), actor);
            throw re;
        }

        long bindings = provenance.countBindings(result);
        provenance.recordRead(touched, sparql, "SUCCESS",
                bindings, System.currentTimeMillis() - started, null, actor);
        return result;
    }

    // Private helpers

    private Map<String, Repository> reposBySourceId() {
        Map<String, Repository> m = new LinkedHashMap<>();
        m.put(SRC_PG,    pgRepo);
        m.put(SRC_MYSQL, mysqlRepo);
        m.put(SRC_MONGO, mongoRepo);
        m.put(SRC_PUBS,  pubsRepo);

        m.put(SRC_LOANS, loansRepo);
        return m;
    }

    /**
     * Reads connection details from the source service. csaUrl is a fallback
     * for sources that register an adapter without a source service.
     * JDBC sources return credentials and a driver; graph sources return a
     * SPARQL endpoint.
     */
    private Map<String, Object> readConnection(String sourceId) throws Exception {
        SourceDescriptor src = catalogue.findById(sourceId).orElseThrow(() ->
                new IllegalStateException("Catalogue has no source '" + sourceId + "'"));

        String baseUrl;
        String path;
        if (src.getSacUrl() != null && !src.getSacUrl().isBlank()) {
            baseUrl = src.getSacUrl();
            path    = "/sac/connection";
        } else if (src.getCsaUrl() != null && !src.getCsaUrl().isBlank()) {
            baseUrl = src.getCsaUrl();
            path    = "/csa/connection";
        } else {
            throw new IllegalStateException(
                    "Source '" + sourceId + "' has neither sacUrl nor csaUrl in the catalogue");
        }
        return metadata.fetchConnectionFromSac(baseUrl, path);
    }

    private OntopRepository buildRepository(String name,
                                            File   ontologyFile,
                                            File   mappingFile,
                                            Map<String, Object> conn) throws Exception {
        log.info("Building Ontop repository for {}…", name);

        OntopSQLOWLAPIConfiguration config = OntopSQLOWLAPIConfiguration.defaultBuilder()
                .ontologyFile(ontologyFile)
                .nativeOntopMappingFile(mappingFile)
                .jdbcUrl((String)      conn.get("jdbcUrl"))
                .jdbcUser((String)     conn.get("username"))
                .jdbcPassword((String) conn.get("password"))
                .jdbcDriver((String)   conn.get("driverClass"))
                .build();

        return OntopRepository.defaultRepository(config);
    }

    private String executeOn(OntopRepository repo, String sparql) {
        try (RepositoryConnection conn = repo.getConnection();
             TupleQueryResult result   = conn.prepareTupleQuery(sparql).evaluate()) {

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            QueryResultIO.writeTuple(result, TupleQueryResultFormat.JSON, baos);
            return baos.toString(StandardCharsets.UTF_8);

        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Ontop SPARQL execution failed: " + e.getMessage(), e);
        }
    }
}
