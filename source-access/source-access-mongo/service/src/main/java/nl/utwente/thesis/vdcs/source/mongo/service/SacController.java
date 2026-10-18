package nl.utwente.thesis.vdcs.source.mongo.service;

import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/** Forwards source access operations to the MongoDB canonical adapter. */
@RestController
@RequestMapping("/sac")
public class SacController {

    private static final Logger log = LoggerFactory.getLogger(SacController.class);

    @Value("${vdcs.csa.url}")
    private String csaUrl;

    private final RestTemplate restTemplate;

    public SacController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/connection")
    public Map<String, Object> connection() {
        // Forward to the CSA - the mediator reads through the same H2
        // endpoint the CSA exposes.
        @SuppressWarnings("unchecked")
        Map<String, Object> upstream = restTemplate.getForObject(
                csaUrl + "/csa/connection", Map.class);
        return upstream;
    }

    @PostMapping("/write")
    public CompletedOperation write(@RequestBody CanonicalUpdate update) {
        log.debug("Forwarding canonical update to CSA: {}", update);
        return restTemplate.postForObject(
                csaUrl + "/csa/write", update, CompletedOperation.class);
    }

    @PostMapping("/compensate")
    public ResponseEntity<Void> compensate(@RequestBody CompletedOperation handle) {
        log.debug("Forwarding compensation to CSA: {}", handle);
        restTemplate.postForEntity(
                csaUrl + "/csa/compensate", handle, Void.class);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> csaHealth = restTemplate.getForObject(
                    csaUrl + "/csa/health", Map.class);
            return Map.of(
                    "status",    "UP",
                    "sourceId",  "student-activities",
                    "csaHealth", csaHealth);
        } catch (Exception e) {
            return Map.of(
                    "status",    "DEGRADED",
                    "sourceId",  "student-activities",
                    "csaError",  e.getMessage());
        }
    }
}
