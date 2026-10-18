package nl.utwente.thesis.vdcs.source.publications.service;

import jakarta.annotation.PostConstruct;
import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import nl.utwente.thesis.vdcs.secret.client.SecretStoreClient;
import nl.utwente.thesis.vdcs.secret.client.SourceSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates canonical publication updates to the source vocabulary and
 * stores the subject's previous triples for compensation.
 */
@Service
public class PublicationsWriteService {

    private static final Logger log = LoggerFactory.getLogger(PublicationsWriteService.class);

    private static final String SOURCE_ID = "research-publications";
    private static final String ENTITY    = "Publication";

    // Native (source) IRI space and vocabulary - see publications.ttl.
    private static final String NATIVE_IRI_PREFIX = "https://research.utwente.nl/publication/";
    private static final String PREFIXES =
            "PREFIX pub: <https://research.utwente.nl/vocab#>\n" +
            "PREFIX dct: <http://purl.org/dc/terms/>\n";

    /** Canonical field to (native predicate, isString). Order is the insert order. */
    private record Native(String predicate, boolean isString) {}
    private static final Map<String, Native> FIELD_MAP = new LinkedHashMap<>();
    static {
        FIELD_MAP.put("publicationTitle",           new Native("dct:title",                 true));
        FIELD_MAP.put("publicationYear",            new Native("pub:year",                  false));
        FIELD_MAP.put("publicationVenue",           new Native("pub:venue",                 true));
        FIELD_MAP.put("publicationAuthorStudentId", new Native("pub:authorStudentNumber",   false));
    }

    @Value("${vdcs.secret-store.url}")   private String secretStoreUrl;
    @Value("${vdcs.secret-store.token}") private String serviceToken;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private String queryEndpoint;    // for pre-image snapshots (CONSTRUCT)
    private String updateEndpoint;   // for the writes themselves (SPARQL Update)
    private String authHeader;       // HTTP Basic for the (protected) update endpoint

    @PostConstruct
    public void init() throws Exception {
        SecretStoreClient secrets = new SecretStoreClient(secretStoreUrl, serviceToken);
        SourceSecret s = secrets.fetch(SOURCE_ID);
        this.queryEndpoint  = s.getSparqlEndpoint();
        this.updateEndpoint = s.getSparqlUpdateEndpoint();
        if (updateEndpoint == null || updateEndpoint.isBlank()) {
            throw new IllegalStateException(
                    "research-publications has no sparqlUpdateEndpoint in the secret store — "
                  + "the source cannot accept writes");
        }
        // The repository's update endpoint is credential-protected; the
        // username/password are system-level secrets held in the store.
        if (s.getUsername() != null && !s.getUsername().isBlank()) {
            String creds = s.getUsername() + ":" + (s.getPassword() == null ? "" : s.getPassword());
            this.authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
        }
        log.info("Publications SAC write path ready; updates -> {} (auth: {})",
                updateEndpoint, authHeader != null);
    }

    // apply

    public CompletedOperation apply(CanonicalUpdate update) throws Exception {
        requirePublication(update.getEntityType());
        Object id = switch (update.getOperation()) {
            case INSERT -> requireId(update.getPayload());
            case UPDATE, DELETE -> requireId(update.getFilter());
        };
        String iri = iri(id);

        // Pre-image snapshot of the subject (empty if it does not yet exist).
        String preImage = snapshot(iri);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("subject",  iri);
        snap.put("preImage", preImage);

        String sparql = switch (update.getOperation()) {
            case INSERT -> buildInsert(iri, update.getPayload());
            case UPDATE -> buildUpdate(iri, update.getPayload());
            case DELETE -> "DELETE WHERE { " + iri + " ?p ?o }";
        };
        runUpdate(sparql);

        log.info("{} Publication {} in the research repository", update.getOperation(), iri);
        return new CompletedOperation(SOURCE_ID, update, snap);
    }

    // compensate

    public void compensate(CompletedOperation handle) throws Exception {
        String iri      = (String) handle.getSnapshot().get("subject");
        String preImage = (String) handle.getSnapshot().getOrDefault("preImage", "");

        // Restore the pre-image: clear the subject, then re-assert whatever
        // was there before (nothing, for a freshly inserted publication).
        StringBuilder sb = new StringBuilder("DELETE WHERE { ").append(iri).append(" ?p ?o }");
        if (preImage != null && !preImage.isBlank()) {
            sb.append(" ;\nINSERT DATA {\n").append(preImage).append("\n}");
        }
        runUpdate(sb.toString());
        log.info("Compensated [{}] Publication {} (restored pre-image)", SOURCE_ID, iri);
    }

    // SPARQL construction

    private static String buildInsert(String iri, Map<String, Object> payload) {
        List<String> triples = new ArrayList<>();
        triples.add("a pub:Publication");
        appendFieldTriples(triples, payload);
        return PREFIXES + "INSERT DATA {\n  " + iri + " "
                + String.join(" ;\n    ", triples) + " .\n}";
    }

    private static String buildUpdate(String iri, Map<String, Object> payload) {
        // One DELETE-old / INSERT-new pair per changed field. Distinct
        // wildcard variables keep the operations independent.
        StringBuilder sb = new StringBuilder(PREFIXES);
        int i = 0;
        List<String> ops = new ArrayList<>();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            Native n = FIELD_MAP.get(e.getKey());
            if (n == null) continue;   // publicationId is an identity, not a value
            String var = "?v" + i++;
            ops.add("DELETE { " + iri + " " + n.predicate() + " " + var + " } "
                  + "WHERE { " + iri + " " + n.predicate() + " " + var + " }");
            ops.add("INSERT DATA { " + iri + " " + n.predicate() + " "
                  + literal(n, e.getValue()) + " }");
        }
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("UPDATE on Publication had no recognised fields");
        }
        sb.append(String.join(" ;\n", ops));
        return sb.toString();
    }

    /** Appends "pred value" triples for every recognised field (expanding lists). */
    private static void appendFieldTriples(List<String> triples, Map<String, Object> payload) {
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            Native n = FIELD_MAP.get(e.getKey());
            if (n == null) continue;   // publicationId lives in the IRI, not a triple
            Object v = e.getValue();
            if (v instanceof Collection<?> col) {
                for (Object item : col) triples.add(n.predicate() + " " + literal(n, item));
            } else {
                triples.add(n.predicate() + " " + literal(n, v));
            }
        }
    }

    /** Formats a value as a SPARQL literal: quoted string, or bare integer. */
    private static String literal(Native n, Object value) {
        if (n.isString()) {
            return "\"" + escape(String.valueOf(value)) + "\"";
        }
        // integer-valued field
        long asLong;
        if (value instanceof Number num) {
            asLong = num.longValue();
        } else {
            try {
                asLong = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "Expected an integer for " + n.predicate() + " but got '" + value + "'");
            }
        }
        return Long.toString(asLong);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // HTTP to the source's SPARQL endpoints

    private void runUpdate(String sparqlUpdate) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(updateEndpoint))
                .header("Content-Type", "application/sparql-update")
                .POST(HttpRequest.BodyPublishers.ofString(sparqlUpdate));
        if (authHeader != null) b.header("Authorization", authHeader);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "SPARQL Update against the research repository failed: HTTP "
                  + resp.statusCode() + " body=" + resp.body());
        }
    }

    /** CONSTRUCTs the subject's current triples as N-Triples (empty if none). */
    private String snapshot(String iri) throws Exception {
        String construct = "CONSTRUCT { " + iri + " ?p ?o } WHERE { " + iri + " ?p ?o }";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(queryEndpoint))
                .header("Content-Type", "application/sparql-query")
                .header("Accept", "application/n-triples")
                .POST(HttpRequest.BodyPublishers.ofString(construct))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Pre-image snapshot query failed: HTTP " + resp.statusCode()
                  + " body=" + resp.body());
        }
        return resp.body() == null ? "" : resp.body().trim();
    }

    // helpers

    private static void requirePublication(String entity) {
        if (!ENTITY.equals(entity)) {
            throw new IllegalArgumentException(
                    "Publications SAC can only write Publication, not '" + entity + "'");
        }
    }

    private static Object requireId(Map<String, Object> from) {
        Object id = from.get("publicationId");
        if (id == null) {
            throw new IllegalArgumentException(
                    "Publication write requires 'publicationId' (in payload for INSERT, "
                  + "in filter for UPDATE/DELETE)");
        }
        return id;
    }

    private static String iri(Object id) {
        String s = String.valueOf(id).trim();
        return "<" + NATIVE_IRI_PREFIX + s + ">";
    }
}
