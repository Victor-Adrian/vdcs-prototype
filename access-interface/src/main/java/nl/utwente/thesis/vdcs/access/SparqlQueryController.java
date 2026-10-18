package nl.utwente.thesis.vdcs.access;

import jakarta.servlet.http.HttpServletRequest;
import nl.utwente.thesis.vdcs.access.auth.IamClient;
import nl.utwente.thesis.vdcs.access.auth.Principal;
import nl.utwente.thesis.vdcs.access.auth.QueryAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Authorizes SPARQL reads and forwards them to the semantic mediator.
 * Self-scoped queries are restricted to the principal's own data.
 */
@RestController
@RequestMapping("/api/query")
public class SparqlQueryController {

    private static final Logger log = LoggerFactory.getLogger(SparqlQueryController.class);

    private static final MediaType SPARQL_QUERY =
            MediaType.parseMediaType("application/sparql-query");
    private static final MediaType SPARQL_RESULTS_JSON =
            MediaType.parseMediaType("application/sparql-results+json");

    private final RestTemplate     restTemplate;
    private final String           mediatorUrl;
    private final QueryAuthorizer  authorizer;
    private final IamClient        iam;

    public SparqlQueryController(RestTemplate restTemplate,
                                 @Value("${vdcs.mediator.url}") String mediatorUrl,
                                 QueryAuthorizer authorizer,
                                 IamClient iam) {
        this.restTemplate = restTemplate;
        this.mediatorUrl  = mediatorUrl;
        this.authorizer   = authorizer;
        this.iam          = iam;
    }

    @GetMapping(produces = "application/sparql-results+json")
    public ResponseEntity<String> queryGet(@RequestParam("query") String sparql,
                                           HttpServletRequest request) {
        return authorizeAndForward(sparql, request, true);
    }

    @PostMapping(
        consumes = "application/sparql-query",
        produces = "application/sparql-results+json"
    )
    public ResponseEntity<String> queryPost(@RequestBody String sparql,
                                            HttpServletRequest request) {
        return authorizeAndForward(sparql, request, false);
    }

    // enforcement + forwarding

    private ResponseEntity<String> authorizeAndForward(String sparql,
                                                       HttpServletRequest request,
                                                       boolean viaGet) {
        Principal principal = (Principal) request.getAttribute(IamFilter.PRINCIPAL_ATTR);
        // principal is never null here: IamFilter rejects unauthenticated /api/* calls.

        QueryAuthorizer.Decision decision = authorizer.authorize(principal, sparql);
        if (!decision.allowed()) {
            log.info("DENY read for {}: {}", principal.getIdentity(), decision.reason());
            iam.reportAccess(Map.of(
                    "timestamp", Instant.now().toString(),
                    "type",      "ACCESS",
                    "operation", "READ",
                    "identity",  principal.getIdentity(),
                    "decision",  "DENY",
                    "reason",    decision.reason(),
                    "entities",  decision.entities()));
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Access denied: " + decision.reason());
        }

        String effectiveQuery = decision.rewrittenQuery();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(SPARQL_RESULTS_JSON));
        headers.set("X-VDCS-Actor", principal.getIdentity());

        ResponseEntity<String> response;
        if (viaGet) {
            String url = UriComponentsBuilder.fromUriString(mediatorUrl + "/sparql")
                    .queryParam("query", effectiveQuery)
                    .toUriString();
            response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
        } else {
            headers.setContentType(SPARQL_QUERY);
            response = restTemplate.exchange(mediatorUrl + "/sparql", HttpMethod.POST,
                    new HttpEntity<>(effectiveQuery, headers), String.class);
        }
        return ResponseEntity.ok(response.getBody());
    }
}
