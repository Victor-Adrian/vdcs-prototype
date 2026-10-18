package nl.utwente.thesis.vdcs.orchestrator.service;

import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness probe for the Write Orchestrator Service, matching the
 * /health convention of the other backend services.
 */
@RestController
@RequestMapping("/orchestrator")
public class HealthController {

    private final SourceCapabilityCatalogue catalogue;

    public HealthController(SourceCapabilityCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        long writable = catalogue.getSources().stream()
                .filter(s -> s.getCapabilities().contains("write"))
                .count();
        return Map.of("status", "UP", "writableSources", writable);
    }
}
