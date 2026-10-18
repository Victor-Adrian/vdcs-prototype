package nl.utwente.thesis.vdcs.access;

import jakarta.servlet.http.HttpServletRequest;
import nl.utwente.thesis.vdcs.access.auth.IamClient;
import nl.utwente.thesis.vdcs.access.auth.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * Checks write permission and forwards canonical or SPARQL updates to
 * the write orchestrator with the caller's identity.
 */
@RestController
@RequestMapping("/api/update")
public class UpdateController {

    private static final Logger log = LoggerFactory.getLogger(UpdateController.class);

    private final RestTemplate restTemplate;
    private final String       orchestratorUrl;
    private final IamClient    iam;

    public UpdateController(RestTemplate restTemplate,
                            @Value("${vdcs.orchestrator.url}") String orchestratorUrl,
                            IamClient iam) {
        this.restTemplate    = restTemplate;
        this.orchestratorUrl = orchestratorUrl;
        this.iam             = iam;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<String> updateJson(@RequestBody String body,
                                             HttpServletRequest request) {
        return enforceAndForward(body, MediaType.APPLICATION_JSON, request);
    }

    @PostMapping(consumes = "application/sparql-update")
    public ResponseEntity<String> updateSparql(@RequestBody String body,
                                               HttpServletRequest request) {
        return enforceAndForward(body,
                MediaType.parseMediaType("application/sparql-update"), request);
    }

    // enforcement + forwarding

    private ResponseEntity<String> enforceAndForward(String body, MediaType contentType,
                                                     HttpServletRequest request) {
        Principal principal = (Principal) request.getAttribute(IamFilter.PRINCIPAL_ATTR);

        if (!principal.isMayWrite()) {
            log.info("DENY write for {}: principal is read-only", principal.getIdentity());
            iam.reportAccess(Map.of(
                    "timestamp", Instant.now().toString(),
                    "type",      "ACCESS",
                    "operation", "WRITE",
                    "identity",  principal.getIdentity(),
                    "decision",  "DENY",
                    "reason",    "principal has no write capability"));
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Access denied: " + principal.getIdentity()
                            + " is not permitted to perform writes.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set("X-VDCS-Actor", principal.getIdentity());

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    orchestratorUrl + "/orchestrator/write", HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            return ResponseEntity.status(resp.getStatusCode()).body(resp.getBody());
        } catch (HttpStatusCodeException e) {
            // Propagate the orchestrator's error status + body to the client.
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getResponseBodyAsString());
        }
    }
}
