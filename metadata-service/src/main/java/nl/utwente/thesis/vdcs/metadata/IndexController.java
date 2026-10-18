package nl.utwente.thesis.vdcs.metadata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Lists the metadata service endpoints. */
@RestController
@RequestMapping("/metadata")
public class IndexController {

    @GetMapping
    public Map<String, Object> index() {
        return Map.of(
                "service",     "vdcs-metadata-service",
                "raComponent", "Metadata Governance Component",
                "fairAlignment", Map.of(
                        "findable",     "Each source and artefact has a persistent IRI under /metadata/sources/{id} and /metadata/artefacts/{id}.",
                        "accessible",   "Standard HTTP/JSON. No authentication is required in the PoC; a production realisation would gate writes (POST /metadata/provenance) behind the IAM service.",
                        "interoperable","Source descriptors carry Dublin Core terms (title, description, license, created, modified, conformsTo). Artefact descriptors declare a MIME format and a conformsTo back-reference to the ontology version they target.",
                        "reusable",     "Every source carries an explicit SPDX license and owner field; every artefact carries a version, license, and modification date."),
                "endpoints", List.of(
                        endpoint("GET",  "/metadata/sources",                  "List all registered sources (with sacUrl/csaUrl, no credentials)."),
                        endpoint("GET",  "/metadata/sources/{id}",             "Single source descriptor."),
                        endpoint("GET",  "/metadata/sources/{id}/license",     "Licence and owner of one source."),
                        endpoint("GET",  "/metadata/artefacts",                "List all governed semantic artefacts."),
                        endpoint("GET",  "/metadata/artefacts/{id}",           "Artefact descriptor (version, license, conformsTo)."),
                        endpoint("GET",  "/metadata/artefacts/{id}/content",   "Raw artefact bytes (TTL, OBDA)."),
                        endpoint("POST", "/metadata/provenance",               "Record one provenance event."),
                        endpoint("GET",  "/metadata/provenance",               "Recent events (filter by operation, actor, sourceId, since)."),
                        endpoint("GET",  "/metadata/provenance/{id}",          "One event by id."),
                        endpoint("GET",  "/metadata/provenance/summary",       "Counts and distinct actors/sources.")
                ));
    }

    private static Map<String, String> endpoint(String method, String path, String description) {
        return Map.of("method", method, "path", path, "description", description);
    }
}
