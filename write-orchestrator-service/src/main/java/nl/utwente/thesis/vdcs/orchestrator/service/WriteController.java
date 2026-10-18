package nl.utwente.thesis.vdcs.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.utwente.thesis.vdcs.orchestrator.WriteOrchestrator;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import nl.utwente.thesis.vdcs.orchestrator.service.provenance.WriteProvenanceRecorder;
import nl.utwente.thesis.vdcs.orchestrator.service.sparql.SparqlUpdateTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Accepts canonical or SPARQL updates and records their outcome and actor. */
@RestController
@RequestMapping("/orchestrator/write")
public class WriteController {

    private static final Logger log = LoggerFactory.getLogger(WriteController.class);

    private final WriteOrchestrator       orchestrator;
    private final SparqlUpdateTranslator  translator;
    private final ObjectMapper            jackson;
    private final WriteProvenanceRecorder provenance;

    public WriteController(WriteOrchestrator       orchestrator,
                           SparqlUpdateTranslator  translator,
                           ObjectMapper            jackson,
                           WriteProvenanceRecorder provenance) {
        this.orchestrator = orchestrator;
        this.translator   = translator;
        this.jackson      = jackson;
        this.provenance   = provenance;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Void> writeJson(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-VDCS-Actor", required = false) String actor) throws Exception {
        CanonicalUpdate update = jackson.convertValue(body, CanonicalUpdate.class);
        log.info("Write by {}: {}", actor, update);
        executeAndAudit(List.of(update), jackson.writeValueAsString(body), actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(consumes = "application/sparql-update")
    public ResponseEntity<Void> writeSparql(
            @RequestBody String sparqlUpdate,
            @RequestHeader(value = "X-VDCS-Actor", required = false) String actor) throws Exception {
        List<CanonicalUpdate> updates = translator.translate(sparqlUpdate, actor);
        log.info("Write by {}: SPARQL Update → {} canonical update(s)", actor, updates.size());
        executeAndAudit(updates, sparqlUpdate, actor);
        return ResponseEntity.noContent().build();
    }

    private void executeAndAudit(List<CanonicalUpdate> updates, String requestDigestSource,
                                 String actor) throws Exception {
        long started = System.currentTimeMillis();
        try {
            // Execute the request atomically across all routed sources.
            orchestrator.executeBatch(updates);
        } catch (Exception e) {
            provenance.recordWrite(updates, requestDigestSource, "FAILURE",
                    System.currentTimeMillis() - started, e.getMessage(), actor);
            throw e;
        }
        provenance.recordWrite(updates, requestDigestSource, "SUCCESS",
                System.currentTimeMillis() - started, null, actor);
    }
}
