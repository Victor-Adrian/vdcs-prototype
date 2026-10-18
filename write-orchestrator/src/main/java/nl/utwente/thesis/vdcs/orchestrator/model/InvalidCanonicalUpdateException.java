package nl.utwente.thesis.vdcs.orchestrator.model;

/** A request that cannot be safely represented by the canonical write contract. */
public class InvalidCanonicalUpdateException extends IllegalArgumentException {
    public InvalidCanonicalUpdateException(String message) { super(message); }
}
