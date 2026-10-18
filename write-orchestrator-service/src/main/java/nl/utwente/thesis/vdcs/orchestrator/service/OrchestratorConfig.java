package nl.utwente.thesis.vdcs.orchestrator.service;

import nl.utwente.thesis.vdcs.metadata.client.MetadataServiceClient;
import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import nl.utwente.thesis.vdcs.metadata.client.SourceDescriptor;
import nl.utwente.thesis.vdcs.orchestrator.CompensationManager;
import nl.utwente.thesis.vdcs.orchestrator.TransactionCoordinator;
import nl.utwente.thesis.vdcs.orchestrator.UpdateRouter;
import nl.utwente.thesis.vdcs.orchestrator.WriteOrchestrator;
import nl.utwente.thesis.vdcs.orchestrator.executor.RemoteSacWriteExecutor;
import nl.utwente.thesis.vdcs.orchestrator.executor.WriteExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds the catalogue-backed executors and write coordination services. */
@Configuration
public class OrchestratorConfig {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorConfig.class);

    @Value("${vdcs.metadata.url:http://localhost:8082}")
    private String metadataUrl;

    @Bean
    public MetadataServiceClient metadataServiceClient() {
        log.info("Write orchestrator will reach metadata service at {}", metadataUrl);
        return new MetadataServiceClient(metadataUrl);
    }

    @Bean
    public SourceCapabilityCatalogue sourceCapabilityCatalogue(MetadataServiceClient client) {
        try {
            SourceCapabilityCatalogue cat = client.fetchCatalogue();
            log.info("Write orchestrator loaded {} sources from metadata service",
                    cat.getSources().size());
            return cat;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not fetch catalogue from metadata service at " + metadataUrl
                  + " — is the metadata-service running on port 8082?", e);
        }
    }

    /**
     * One RemoteSacWriteExecutor per writable source, bound to
     * the source's sacUrl, reporting its writable entity types so
     * the UpdateRouter can route canonical updates by entity.
     */
    @Bean
    public List<WriteExecutor> writeExecutors(SourceCapabilityCatalogue catalogue) {
        List<WriteExecutor> execs = new ArrayList<>();
        for (SourceDescriptor src : catalogue.getSources()) {
            if (!src.getCapabilities().contains("write")) continue;
            if (src.getSacUrl() == null || src.getSacUrl().isBlank()) {
                throw new IllegalStateException(
                        "Source '" + src.getId() + "' is writable but has no sacUrl in catalogue.yml");
            }
            Set<String> entities = new HashSet<>(src.getEntities());
            log.info("Wiring remote SAC executor for source '{}' at {} (entities: {})",
                    src.getId(), src.getSacUrl(), entities);
            execs.add(new RemoteSacWriteExecutor(src.getId(), entities, src.getSacUrl()));
        }
        return execs;
    }

    @Bean
    public CompensationManager compensationManager(List<WriteExecutor> writeExecutors) {
        return new CompensationManager(writeExecutors);
    }

    @Bean
    public TransactionCoordinator transactionCoordinator(List<WriteExecutor> writeExecutors,
                                                         CompensationManager compensation) {
        return new TransactionCoordinator(writeExecutors, compensation);
    }

    @Bean
    public UpdateRouter updateRouter(SourceCapabilityCatalogue catalogue) {
        return new UpdateRouter(catalogue);
    }

    @Bean
    public WriteOrchestrator writeOrchestrator(UpdateRouter router,
                                               TransactionCoordinator coordinator) {
        return new WriteOrchestrator(router, coordinator);
    }
}
