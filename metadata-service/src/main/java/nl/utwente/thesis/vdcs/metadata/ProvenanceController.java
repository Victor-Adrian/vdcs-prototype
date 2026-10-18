package nl.utwente.thesis.vdcs.metadata;

import nl.utwente.thesis.vdcs.metadata.model.ProvenanceEvent;
import nl.utwente.thesis.vdcs.metadata.service.ProvenanceLog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Records provenance events and exposes event history and summaries. */
@RestController
@RequestMapping("/metadata/provenance")
public class ProvenanceController {

    private final ProvenanceLog log;

    public ProvenanceController(ProvenanceLog log) {
        this.log = log;
    }

    @PostMapping
    public ResponseEntity<ProvenanceEvent> record(@RequestBody ProvenanceEvent event) {
        return ResponseEntity.ok(log.record(event));
    }

    @GetMapping
    public List<ProvenanceEvent> list(
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        Instant sinceInstant = (since == null || since.isBlank()) ? null : Instant.parse(since);
        if (operation == null && actor == null && sourceId == null && sinceInstant == null) {
            return log.recent(limit);
        }
        return log.filter(operation, actor, sourceId, sinceInstant, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProvenanceEvent> byId(@PathVariable String id) {
        return log.byId(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of(
                "totalEvents",     log.size(),
                "distinctActors",  log.distinctActors(),
                "distinctSources", log.distinctSources());
    }
}
