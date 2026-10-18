package nl.utwente.thesis.vdcs.orchestrator;

import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.executor.WriteExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reverts completed source operations in reverse order on a best-effort basis. */
public class CompensationManager {

    private static final Logger log = LoggerFactory.getLogger(CompensationManager.class);

    private final Map<String, WriteExecutor> executorsBySourceId;

    /**
     * Builds a source-id to executor lookup table from the registered
     * executors. Each executor declares its own source id via
     * WriteExecutor.sourceId().
     */
    public CompensationManager(Collection<WriteExecutor> executors) {
        Map<String, WriteExecutor> map = new LinkedHashMap<>();
        for (WriteExecutor e : executors) {
            map.put(e.sourceId(), e);
        }
        this.executorsBySourceId = Map.copyOf(map);
    }

    /**
     * Reverts the given list of completed operations in reverse order.
     */
    public void compensate(List<CompletedOperation> completed) {
        if (completed == null || completed.isEmpty()) return;

        log.warn("Compensating {} completed operation(s) due to a downstream failure",
                 completed.size());

        // Reverse-iterate so the most recent op is undone first
        List<CompletedOperation> stack = new ArrayList<>(completed);
        Collections.reverse(stack);

        for (CompletedOperation op : stack) {
            WriteExecutor exec = executorsBySourceId.get(op.getSourceId());
            if (exec == null) {
                log.error("No executor registered for source '{}'; cannot compensate {}",
                          op.getSourceId(), op);
                continue;
            }
            try {
                exec.compensate(op);
            } catch (Exception e) {
                log.error("Compensation failed for {}: {}", op, e.getMessage(), e);
                // continue with the rest of the stack - best-effort
            }
        }
    }
}
