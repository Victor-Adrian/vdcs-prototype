package nl.utwente.thesis.vdcs.orchestrator.service.sparql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates update WHERE clauses through the mediator and returns their bindings. */
@Component
public class MediatorSelectClient {

    private final String        mediatorUrl;
    private final HttpClient    http;
    private final ObjectMapper  json = new ObjectMapper();
    private final ValueFactory  vf   = SimpleValueFactory.getInstance();

    public MediatorSelectClient(
            @org.springframework.beans.factory.annotation.Value("${vdcs.mediator.url:http://localhost:8081}")
            String mediatorUrl) {
        this.mediatorUrl = mediatorUrl.endsWith("/") ? mediatorUrl.substring(0, mediatorUrl.length() - 1) : mediatorUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Runs a SELECT and returns one map (variable to value) per result
     * row. Unbound variables are excluded from a row's map.
     */
    public List<Map<String, Value>> select(String sparqlSelect, String actor) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(mediatorUrl + "/sparql"))
                    .header("Content-Type", "application/sparql-query")
                    .header("Accept", "application/sparql-results+json")
                    .POST(HttpRequest.BodyPublishers.ofString(sparqlSelect));
            if (actor != null && !actor.isBlank()) b.header("X-VDCS-Actor", actor);

            HttpResponse<String> resp = http.send(b.build(), BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Mediator WHERE evaluation failed: HTTP "
                        + resp.statusCode() + " body=" + resp.body());
            }
            return parse(resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not evaluate WHERE clause via mediator: "
                    + e.getMessage(), e);
        }
    }

    private List<Map<String, Value>> parse(String resultsJson) throws Exception {
        JsonNode root = json.readTree(resultsJson);
        JsonNode bindings = root.path("results").path("bindings");
        List<Map<String, Value>> rows = new ArrayList<>();
        if (!bindings.isArray()) return rows;
        for (JsonNode row : bindings) {
            Map<String, Value> r = new LinkedHashMap<>();
            row.fieldNames().forEachRemaining(var -> r.put(var, toValue(row.get(var))));
            rows.add(r);
        }
        return rows;
    }

    private Value toValue(JsonNode cell) {
        String type  = cell.path("type").asText();
        String value = cell.path("value").asText();
        return switch (type) {
            case "uri"   -> vf.createIRI(value);
            case "bnode" -> vf.createBNode(value);
            case "literal", "typed-literal" -> {
                if (cell.hasNonNull("datatype")) {
                    yield vf.createLiteral(value, vf.createIRI(cell.get("datatype").asText()));
                }
                if (cell.hasNonNull("xml:lang")) {
                    yield vf.createLiteral(value, cell.get("xml:lang").asText());
                }
                yield vf.createLiteral(value);
            }
            default -> vf.createLiteral(value);
        };
    }
}
