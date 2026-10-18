package nl.utwente.thesis.vdcs.mediator;

import nl.utwente.thesis.vdcs.metadata.client.MetadataServiceClient;
import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loads the source catalogue at startup and provides the metadata client.
 * Startup fails if the metadata service is unavailable.
 */
@Configuration
public class CatalogueConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogueConfig.class);

    @Value("${vdcs.metadata.url:http://localhost:8082}")
    private String metadataUrl;

    @Bean
    public MetadataServiceClient metadataServiceClient() {
        log.info("Mediator will reach metadata service at {}", metadataUrl);
        return new MetadataServiceClient(metadataUrl);
    }

    @Bean
    public SourceCapabilityCatalogue sourceCapabilityCatalogue(MetadataServiceClient client) {
        try {
            SourceCapabilityCatalogue cat = client.fetchCatalogue();
            log.info("Mediator loaded {} sources from metadata service",
                    cat.getSources().size());
            return cat;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not fetch catalogue from metadata service at " + metadataUrl
                  + " — is the metadata-service running on port 8082?", e);
        }
    }
}
