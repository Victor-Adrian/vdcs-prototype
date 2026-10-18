package nl.utwente.thesis.vdcs.secret;

import nl.utwente.thesis.vdcs.secret.client.SourceSecret;
import nl.utwente.thesis.vdcs.secret.model.SecretEntry;
import nl.utwente.thesis.vdcs.secret.service.CredentialAuditLog;
import nl.utwente.thesis.vdcs.secret.service.SecretRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Exposes authorized credential lookup, audit history, and health endpoints. */
@RestController
@RequestMapping("/secrets")
public class SecretController {

    private static final Logger log = LoggerFactory.getLogger(SecretController.class);

    private final SecretRegistry    registry;
    private final CredentialAuditLog audit;

    public SecretController(SecretRegistry registry, CredentialAuditLog audit) {
        this.registry = registry;
        this.audit    = audit;
    }

    @GetMapping("/{sourceId}")
    public ResponseEntity<SourceSecret> fetch(
            @PathVariable String sourceId,
            @RequestHeader(value = "X-Service-Token", required = false) String serviceToken) {

        String caller = mask(serviceToken);

        if (registry.byId(sourceId).isEmpty()) {
            audit.record(sourceId, caller, "DENIED", "unknown source");
            return ResponseEntity.notFound().build();
        }
        if (!registry.isAuthorised(sourceId, serviceToken)) {
            audit.record(sourceId, caller, "DENIED", "token not authorised for this source");
            log.warn("Refused credential request for '{}' from caller {}", sourceId, caller);
            return ResponseEntity.status(401).build();
        }

        SecretEntry e = registry.byId(sourceId).get();
        audit.record(sourceId, caller, "GRANTED", null);
        log.info("Served credentials for '{}' to caller {}", sourceId, caller);
        return ResponseEntity.ok(toDto(e));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog(
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return audit.recent(limit);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "sources", registry.size(), "auditEvents", audit.size());
    }

    // helpers

    private static SourceSecret toDto(SecretEntry e) {
        SourceSecret s = new SourceSecret();
        s.setSourceId(e.getSourceId());
        s.setEngine(e.getEngine());
        s.setJdbcUrl(e.getJdbcUrl());
        s.setUsername(e.getUsername());
        s.setPassword(e.getPassword());
        s.setDriverClass(e.getDriverClass());
        s.setMongoUri(e.getMongoUri());
        s.setDatabase(e.getDatabase());
        s.setSparqlEndpoint(e.getSparqlEndpoint());
        s.setSparqlUpdateEndpoint(e.getSparqlUpdateEndpoint());
        return s;
    }

    /** Masks a service token for audit logging - never log the raw secret. */
    private static String mask(String token) {
        if (token == null || token.isBlank()) return "(none)";
        if (token.length() <= 4) return "****";
        return token.substring(0, 4) + "****";
    }
}
