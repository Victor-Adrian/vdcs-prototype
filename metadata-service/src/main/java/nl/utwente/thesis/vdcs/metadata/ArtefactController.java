package nl.utwente.thesis.vdcs.metadata;

import nl.utwente.thesis.vdcs.metadata.model.ArtefactDescriptor;
import nl.utwente.thesis.vdcs.metadata.service.ArtefactRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes semantic artefact descriptors and their raw content. */
@RestController
@RequestMapping("/metadata/artefacts")
public class ArtefactController {

    private final ArtefactRegistry registry;

    public ArtefactController(ArtefactRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<ArtefactDescriptor> list() {
        return registry.all();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ArtefactDescriptor> single(@PathVariable String id) {
        return registry.byId(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id) throws Exception {
        return registry.byId(id).map(desc -> {
            try {
                byte[] bytes = registry.content(id);
                String filename = suggestedFilename(desc);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(desc.getFormat()))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + filename + "\"")
                        .body(bytes);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read artefact content", e);
            }
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String suggestedFilename(ArtefactDescriptor d) {
        int slash = d.getClasspath().lastIndexOf('/');
        return slash < 0 ? d.getClasspath() : d.getClasspath().substring(slash + 1);
    }
}
