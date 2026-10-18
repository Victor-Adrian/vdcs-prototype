package nl.utwente.thesis.vdcs.orchestrator;

import lombok.Getter;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdateValidator;
import nl.utwente.thesis.vdcs.metadata.client.SourceDescriptor;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Routes canonical updates to all writable owners and coordinates their execution. */
@Getter
public class WriteOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WriteOrchestrator.class);

    private final UpdateRouter           router;
    private final TransactionCoordinator coordinator;

    public WriteOrchestrator(UpdateRouter router, TransactionCoordinator coordinator) {
        this.router      = router;
        this.coordinator = coordinator;
    }

    /**
     * Executes a canonical update across every authoritative source.
     *
     * @param update  update expressed against the canonical write model
     * @throws Exception if the transaction fails after compensation
     */
    public void execute(CanonicalUpdate update) throws Exception {
        executeBatch(List.of(update));
    }

    /**
     * Resolves and executes a batch as one federated transaction. A failure
     * compensates completed sub-operations. Routing is resolved before work
     * begins, so an unroutable update leaves every source unchanged.
     *
     * @param updates updates from one write request
     */
    public void executeBatch(List<CanonicalUpdate> updates) throws Exception {
        // Validate the entire request before routing or invoking any source.
        updates.forEach(CanonicalUpdateValidator::validate);
        List<TransactionCoordinator.SubOperation> subOps = new ArrayList<>();
        for (CanonicalUpdate update : updates) {
            List<SourceDescriptor> targets = router.resolveTargets(update);
            if (targets.isEmpty()) {
                throw new IllegalStateException(
                        "No writable source found in catalogue for entity '"
                      + update.getEntityType() + "'");
            }
            for (SourceDescriptor src : targets) {
                subOps.add(new TransactionCoordinator.SubOperation(src.getId(), update));
            }
        }
        if (subOps.isEmpty()) return;

        log.info("Executing batch of {} update(s) as {} sub-operation(s) in one transaction",
                 updates.size(), subOps.size());
        coordinator.execute(subOps);
    }

}
