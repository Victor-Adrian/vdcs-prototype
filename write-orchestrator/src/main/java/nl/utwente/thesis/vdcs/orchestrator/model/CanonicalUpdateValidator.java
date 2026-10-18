package nl.utwente.thesis.vdcs.orchestrator.model;

import java.util.Map;
import java.util.function.Consumer;

/** Entity contracts checked before any operation in a request is executed. */
public final class CanonicalUpdateValidator {
    private static final Map<String, Consumer<CanonicalUpdate>> CONTRACTS =
            Map.of(FinalGradeOps.ENTITY, FinalGradeOps::validate);

    private CanonicalUpdateValidator() {}

    public static void validate(CanonicalUpdate update) {
        if (update == null || update.getOperation() == null || update.getEntityType() == null) {
            throw new InvalidCanonicalUpdateException("An operation and entityType are required.");
        }
        Consumer<CanonicalUpdate> contract = CONTRACTS.get(update.getEntityType());
        if (contract != null) contract.accept(update);
    }
}
