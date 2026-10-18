package nl.utwente.thesis.vdcs.orchestrator;

import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.executor.WriteExecutor;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes source updates sequentially and compensates completed work after a failure. */
public class TransactionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TransactionCoordinator.class);

    private final Map<String, WriteExecutor> executorsBySourceId;
    private final CompensationManager        compensation;

    /**
     * Builds a source-id to executor lookup table from the registered
     * executors. Each executor declares its own source id via
     * WriteExecutor.sourceId().
     */
    public TransactionCoordinator(Collection<WriteExecutor> executors,
                                  CompensationManager       compensation) {
        Map<String, WriteExecutor> map = new LinkedHashMap<>();
        for (WriteExecutor e : executors) {
            map.put(e.sourceId(), e);
        }
        this.executorsBySourceId = Map.copyOf(map);
        this.compensation        = compensation;
    }

    /**
     * Executes sub-operations in order and compensates completed work after a
     * failure.
     *
     * @param subOperations source and update pairs
     */
    public void execute(List<SubOperation> subOperations) throws Exception {
        List<CompletedOperation> completed = new ArrayList<>();

        for (SubOperation sub : subOperations) {
            WriteExecutor exec = executorsBySourceId.get(sub.sourceId());
            if (exec == null) {
                Exception e = new IllegalStateException(
                        "No write executor registered for source '" + sub.sourceId() + "'");
                compensation.compensate(completed);
                throw e;
            }

            try {
                CompletedOperation handle = exec.apply(sub.update());
                completed.add(handle);
                log.info("Applied sub-op {} → source {}", sub.update(), sub.sourceId());
            } catch (Exception e) {
                log.error("Sub-op failed on source '{}': {}", sub.sourceId(), e.getMessage(), e);
                compensation.compensate(completed);
                throw new TransactionFailedException(
                        "Transaction aborted after " + completed.size()
                      + " successful sub-operation(s); compensation attempted", e);
            }
        }

        log.info("Transaction committed: {} sub-operation(s) applied", completed.size());
    }

    /** Pairs a canonical update with the source id it's destined for. */
    public record SubOperation(String sourceId, CanonicalUpdate update) {}

    /** Thrown when execute() bails out after compensation. */
    public static class TransactionFailedException extends Exception {
        public TransactionFailedException(String msg, Throwable cause) { super(msg, cause); }
    }
}
