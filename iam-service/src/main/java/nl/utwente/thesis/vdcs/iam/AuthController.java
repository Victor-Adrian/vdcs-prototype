package nl.utwente.thesis.vdcs.iam;

import nl.utwente.thesis.vdcs.iam.model.Principal;
import nl.utwente.thesis.vdcs.iam.service.AuthAuditLog;
import nl.utwente.thesis.vdcs.iam.service.PrincipalRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exposes authentication, audit reporting, audit history, and health endpoints. */
@RestController
@RequestMapping("/iam")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final PrincipalRegistry registry;
    private final AuthAuditLog      audit;

    public AuthController(PrincipalRegistry registry, AuthAuditLog audit) {
        this.registry = registry;
        this.audit    = audit;
    }

    @PostMapping("/authenticate")
    public ResponseEntity<Principal> authenticate(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        Optional<Principal> p = registry.resolve(token);
        if (p.isEmpty()) {
            audit.recordAuthentication("(unknown)", "FAILURE", "unrecognised token");
            log.warn("Authentication failed: unrecognised token");
            return ResponseEntity.status(401).build();
        }
        Principal principal = p.get();
        audit.recordAuthentication(principal.getIdentity(), "SUCCESS", null);
        log.info("Authenticated principal '{}' (roles={})",
                principal.getIdentity(), principal.getRoles());
        return ResponseEntity.ok(principal);   // token is @JsonIgnore
    }

    @PostMapping("/audit")
    public ResponseEntity<Void> reportAccess(@RequestBody Map<String, Object> event) {
        audit.recordReported(event);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog(
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return audit.recent(limit);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "principals", registry.size(), "auditEvents", audit.size());
    }
}
