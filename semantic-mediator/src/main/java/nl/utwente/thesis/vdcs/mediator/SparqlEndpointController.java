package nl.utwente.thesis.vdcs.mediator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes GET and POST forms of the SPARQL query endpoint. */
@RestController
@RequestMapping("/sparql")
public class SparqlEndpointController {

    private final OntopMediatorService mediator;

    public SparqlEndpointController(OntopMediatorService mediator) {
        this.mediator = mediator;
    }

    @GetMapping(produces = "application/sparql-results+json")
    public ResponseEntity<String> queryGet(
            @RequestParam("query") String sparql,
            @RequestHeader(value = "X-VDCS-Actor", required = false) String actor) {
        return ResponseEntity.ok(mediator.executeSparql(sparql, actor));
    }

    @PostMapping(
        consumes  = "application/sparql-query",
        produces  = "application/sparql-results+json"
    )
    public ResponseEntity<String> queryPost(
            @RequestBody String sparql,
            @RequestHeader(value = "X-VDCS-Actor", required = false) String actor) {
        return ResponseEntity.ok(mediator.executeSparql(sparql, actor));
    }
}
