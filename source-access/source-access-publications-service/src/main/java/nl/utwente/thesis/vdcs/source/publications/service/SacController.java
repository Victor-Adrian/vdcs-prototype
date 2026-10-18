package nl.utwente.thesis.vdcs.source.publications.service;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import nl.utwente.thesis.vdcs.secret.client.SecretStoreClient;
import nl.utwente.thesis.vdcs.secret.client.SourceSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxies publication reads to the source and handles canonical writes and
 * compensation without exposing the source endpoint.
 */
@RestController
@RequestMapping("/sac")
public class SacController {

    private static final Logger log = LoggerFactory.getLogger(SacController.class);

    private static final String SOURCE_ID = "research-publications";

    @Value("${vdcs.secret-store.url}")   private String secretStoreUrl;
    @Value("${vdcs.secret-store.token}") private String serviceToken;
    @Value("${server.port}")             private int    serverPort;

    private final RestTemplate restTemplate;
    private final PublicationsWriteService writeService;
    private final AtomicLong   proxiedReads = new AtomicLong();

    /** The source's native endpoint - internal to this SAC. */
    private String upstreamEndpoint;

    public SacController(RestTemplate restTemplate, PublicationsWriteService writeService) {
        this.restTemplate = restTemplate;
        this.writeService = writeService;
    }

    @PostConstruct
    public void init() throws Exception {
        // Connection settings live in the Secret & Credential Store,
        // like every other source's - and stay HERE: the mediator only
        // ever sees this SAC's proxy URL.
        SecretStoreClient secrets = new SecretStoreClient(secretStoreUrl, serviceToken);
        SourceSecret s = secrets.fetch(SOURCE_ID);
        this.upstreamEndpoint = s.getSparqlEndpoint();
        log.info("Publications SAC pulled endpoint from secret store; proxying /sac/sparql -> {}",
                upstreamEndpoint);
    }

    @GetMapping("/connection")
    public Map<String, Object> connection() {
        return Map.of(
                "sourceId",       SOURCE_ID,
                "engine",         "sparql",
                // The SAC's own proxy - reads flow through the wrapper.
                "sparqlEndpoint", "http://localhost:" + serverPort + "/sac/sparql");
    }

    /**
     * Transparent SPARQL-protocol proxy: forwards the request (method,
     * query string, content type, accept, body) to the source's native
     * endpoint and mirrors the response back. RDF4J's SPARQLRepository
     * on the mediator side talks to this exactly as it would to Fuseki.
     */
    @RequestMapping(value = "/sparql", method = { RequestMethod.GET, RequestMethod.POST })
    public ResponseEntity<byte[]> sparql(HttpServletRequest request,
                                         @RequestBody(required = false) byte[] body) {
        // NB: java.net.URI, not a String - RestTemplate treats String URLs
        // as templates and would RE-encode the already-encoded query
        // string (%20 becomes %2520), corrupting the proxied query.
        java.net.URI url = java.net.URI.create(upstreamEndpoint
                + (request.getQueryString() != null ? "?" + request.getQueryString() : ""));

        HttpHeaders headers = new HttpHeaders();
        if (request.getContentType() != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, request.getContentType());
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null) {
            headers.set(HttpHeaders.ACCEPT, accept);
        }

        proxiedReads.incrementAndGet();
        log.debug("Proxying SPARQL {} ({} byte body) -> {}",
                request.getMethod(), body == null ? 0 : body.length, upstreamEndpoint);
        try {
            ResponseEntity<byte[]> upstream = restTemplate.exchange(
                    url, HttpMethod.valueOf(request.getMethod()),
                    new HttpEntity<>(body, headers), byte[].class);
            HttpHeaders out = new HttpHeaders();
            if (upstream.getHeaders().getContentType() != null) {
                out.setContentType(upstream.getHeaders().getContentType());
            }
            return new ResponseEntity<>(upstream.getBody(), out, upstream.getStatusCode());
        } catch (HttpStatusCodeException e) {
            // Mirror the source's error verbatim (bad query syntax etc.).
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAsByteArray());
        }
    }

    /**
     * Applies a canonical Publication write, returning a compensation
     * handle (pre-image) for the orchestrator's saga.
     */
    @PostMapping("/write")
    public CompletedOperation write(@RequestBody CanonicalUpdate update) throws Exception {
        log.debug("Received canonical update: {}", update);
        return writeService.apply(update);
    }

    /** Undoes a previously applied write from its compensation handle. */
    @PostMapping("/compensate")
    public ResponseEntity<Void> compensate(@RequestBody CompletedOperation handle) throws Exception {
        log.debug("Received compensation request: {}", handle);
        writeService.compensate(handle);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status",       "UP",
                "sourceId",     SOURCE_ID,
                // Exposes whether publication reads passed through this service.
                "proxiedReads", proxiedReads.get());
    }
}
