package nl.utwente.thesis.vdcs.orchestrator.model;

import java.math.BigDecimal;

/** Handles the shared student/course key for final grades stored in both SQL sources. */
public final class FinalGradeOps {

    /** Canonical entity-type name. */
    public static final String ENTITY = "FinalGrade";

    private FinalGradeOps() {}

    private static final java.util.Set<String> KEY_FIELDS = java.util.Set.of(
            "finalStudentId", "finalCourseId", "finalGradeKey");

    /** Also called at source boundaries to protect direct SAC requests. */
    public static void validate(CanonicalUpdate update) {
        if (update.getOperation() == CanonicalUpdate.OperationType.DELETE) {
            throw new InvalidCanonicalUpdateException(
                    "Deleting FinalGrade is not supported. No changes have been made by this operation.");
        }
        if (update.getOperation() == null) {
            throw new InvalidCanonicalUpdateException("FinalGrade requires INSERT or UPDATE.");
        }
        for (String field : update.getFilter().keySet()) {
            if (!KEY_FIELDS.contains(field)) {
                throw new InvalidCanonicalUpdateException(
                        "Unsupported FinalGrade filter field '" + field
                        + "'. Only finalStudentId, finalCourseId and finalGradeKey are supported.");
            }
        }
        key(update);
        try {
            value(update);
        } catch (IllegalArgumentException e) {
            throw new InvalidCanonicalUpdateException("FinalGrade requires a numeric, non-null finalScore.");
        }
    }

    /** Resolve all supplied key forms; never silently prefer conflicting identifiers. */
    public static int[] key(CanonicalUpdate update) {
        Integer sid = null;
        Integer cid = null;
        for (var fields : java.util.List.of(update.getFilter(), update.getPayload())) {
            if (fields.containsKey("finalStudentId")) {
                sid = agree(sid, identifier(fields.get("finalStudentId")), "student");
            }
            if (fields.containsKey("finalCourseId")) {
                cid = agree(cid, identifier(fields.get("finalCourseId")), "course");
            }
            if (fields.containsKey("finalGradeKey")) {
                Object raw = fields.get("finalGradeKey");
                if (!(raw instanceof String composite) || !composite.matches("\\d+-\\d+")) {
                    throw new InvalidCanonicalUpdateException("finalGradeKey must be '{studentId}-{courseId}'.");
                }
                String[] parts = composite.split("-", 2);
                sid = agree(sid, identifier(parts[0]), "student");
                cid = agree(cid, identifier(parts[1]), "course");
            }
        }
        if (sid == null || cid == null) {
            throw new InvalidCanonicalUpdateException(
                    "FinalGrade requires finalStudentId and finalCourseId, or finalGradeKey '{studentId}-{courseId}'.");
        }
        return new int[]{sid, cid};
    }

    private static Integer agree(Integer previous, int supplied, String part) {
        if (previous != null && previous != supplied) {
            throw new InvalidCanonicalUpdateException("Conflicting FinalGrade " + part + " identifiers.");
        }
        return supplied;
    }

    private static int identifier(Object raw) {
        try {
            if (raw == null) throw new NumberFormatException();
            int result = new BigDecimal(raw.toString()).intValueExact();
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new InvalidCanonicalUpdateException("FinalGrade identifiers must be positive 32-bit integers.");
        }
    }

    /** The new final-grade value (finalScore) from the payload. */
    public static BigDecimal value(CanonicalUpdate u) {
        Object v = u.getPayload() == null ? null : u.getPayload().get("finalScore");
        BigDecimal d = toDecimal(v);
        if (d == null) {
            throw new IllegalArgumentException("FinalGrade payload requires 'finalScore'");
        }
        return d;
    }

    /**
     * Null-safe coercion of a (possibly JSON-roundtripped) numeric value
     * to BigDecimal - snapshots travel through Jackson between the
     * orchestrator and the SACs, so a DECIMAL pre-image may arrive as
     * Double or String.
     */
    public static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(v.toString());
    }

}
