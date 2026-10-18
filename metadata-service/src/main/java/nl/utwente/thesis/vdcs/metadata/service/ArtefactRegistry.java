package nl.utwente.thesis.vdcs.metadata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import nl.utwente.thesis.vdcs.metadata.model.ArtefactDescriptor;
import nl.utwente.thesis.vdcs.metadata.model.ArtefactList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Loads ontology and mapping metadata and serves their classpath content. */
@Service
public class ArtefactRegistry {

    private static final Logger log = LoggerFactory.getLogger(ArtefactRegistry.class);

    private ArtefactList artefacts;

    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource("artefacts.yml").getInputStream()) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            this.artefacts = mapper.readValue(is, ArtefactList.class);
            log.info("ArtefactRegistry loaded {} artefacts from artefacts.yml",
                    artefacts.getArtefacts().size());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load artefacts.yml", e);
        }
    }

    public List<ArtefactDescriptor> all() {
        return Collections.unmodifiableList(artefacts.getArtefacts());
    }

    public Optional<ArtefactDescriptor> byId(String id) {
        return artefacts.getArtefacts().stream()
                .filter(a -> id.equals(a.getId()))
                .findFirst();
    }

    /** Mappings whose appliesTo matches the given source id. */
    public List<ArtefactDescriptor> byAppliesTo(String sourceId) {
        return artefacts.getArtefacts().stream()
                .filter(a -> sourceId.equals(a.getAppliesTo()))
                .toList();
    }

    /** Reads an artefact from the classpath location in artefacts.yml. */
    public byte[] content(String artefactId) throws IOException {
        ArtefactDescriptor desc = byId(artefactId).orElseThrow(() ->
                new IllegalArgumentException("Unknown artefact id: " + artefactId));
        try (InputStream is = new ClassPathResource(desc.getClasspath()).getInputStream()) {
            return is.readAllBytes();
        }
    }
}
