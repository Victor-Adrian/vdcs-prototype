package nl.utwente.thesis.vdcs.metadata;

import nl.utwente.thesis.vdcs.metadata.client.SourceDescriptor;
import nl.utwente.thesis.vdcs.metadata.service.SourceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Exposes source discovery, filtering, descriptors, and licence metadata. */
@RestController
@RequestMapping("/metadata/sources")
public class SourceController {

    private final SourceRegistry registry;

    public SourceController(SourceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<SourceDescriptor> list(
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) String entity) {
        if (capability != null) return registry.byCapability(capability);
        if (entity     != null) return registry.byEntity(entity);
        return registry.all();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SourceDescriptor> single(@PathVariable String id) {
        return registry.byId(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Licence subresource - Reusable side of FAIR. */
    @GetMapping("/{id}/license")
    public ResponseEntity<Map<String, String>> license(@PathVariable String id) {
        return registry.byId(id)
                .map(s -> Map.of(
                        "sourceId",   s.getId(),
                        "license",    nullSafe(s.getLicense()),
                        "licenseUrl", nullSafe(s.getLicenseUrl()),
                        "owner",      nullSafe(s.getOwner())))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
