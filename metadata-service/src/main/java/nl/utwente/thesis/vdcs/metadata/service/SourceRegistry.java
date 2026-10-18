package nl.utwente.thesis.vdcs.metadata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import nl.utwente.thesis.vdcs.metadata.client.SourceDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Loads source metadata from catalogue.yml for the metadata API. */
@Service
public class SourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SourceRegistry.class);

    private SourceCapabilityCatalogue catalogue;

    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource("catalogue.yml").getInputStream()) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            this.catalogue = mapper.readValue(is, SourceCapabilityCatalogue.class);
            log.info("SourceRegistry loaded {} sources from catalogue.yml",
                    catalogue.getSources().size());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load catalogue.yml", e);
        }
    }

    /** All registered sources, in catalogue order. Read-only. */
    public List<SourceDescriptor> all() {
        return Collections.unmodifiableList(catalogue.getSources());
    }

    /** Look up one source by id. */
    public Optional<SourceDescriptor> byId(String id) {
        return catalogue.findById(id);
    }

    /** All sources that declare a capability ("read" or "write"). */
    public List<SourceDescriptor> byCapability(String capability) {
        return catalogue.findByCapability(capability);
    }

    /** All sources authoritative for an ontology entity type. */
    public List<SourceDescriptor> byEntity(String entityType) {
        return catalogue.findByEntity(entityType);
    }
}
