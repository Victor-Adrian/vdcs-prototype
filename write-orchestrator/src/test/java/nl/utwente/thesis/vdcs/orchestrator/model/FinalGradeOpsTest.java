package nl.utwente.thesis.vdcs.orchestrator.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate.OperationType.*;

class FinalGradeOpsTest {
    private CanonicalUpdate update(Map<String,Object> payload, Map<String,Object> filter) {
        return new CanonicalUpdate(UPDATE, "FinalGrade", payload, filter);
    }
    @Test void acceptsMatchingIdentifiersAcrossAllForms() {
        var u = update(Map.of("finalScore", 90, "finalStudentId", 1L, "finalCourseId", "2", "finalGradeKey", "1-2"),
                Map.of("finalStudentId", 1, "finalCourseId", 2, "finalGradeKey", "1-2"));
        assertDoesNotThrow(() -> FinalGradeOps.validate(u));
        assertArrayEquals(new int[]{1,2}, FinalGradeOps.key(u));
    }
    @Test void acceptsCompositeKeyFromSparqlInsert() {
        var u = new CanonicalUpdate(INSERT, "FinalGrade", Map.of("finalGradeKey", "1-2", "finalScore", 82), Map.of());
        assertDoesNotThrow(() -> FinalGradeOps.validate(u));
    }
    @ParameterizedTest @ValueSource(strings={"finalScore", "academicYear", "unknown"})
    void rejectsUnsupportedFilterInsteadOfIgnoringIt(String field) {
        var u=update(Map.of("finalScore",90),Map.of("finalStudentId",1,"finalCourseId",2,field,80));
        assertTrue(assertThrows(InvalidCanonicalUpdateException.class,()->FinalGradeOps.validate(u)).getMessage().contains(field));
    }
    @Test void rejectsConflictingPayloadAndFilterIds() {
        assertThrows(InvalidCanonicalUpdateException.class,()->FinalGradeOps.validate(
                update(Map.of("finalScore",90,"finalStudentId",3),Map.of("finalStudentId",1,"finalCourseId",2))));
    }
    @Test void rejectsConflictingCompositeKey() {
        assertThrows(InvalidCanonicalUpdateException.class,()->FinalGradeOps.validate(
                update(Map.of("finalScore",90,"finalGradeKey","1-3"),Map.of("finalStudentId",1,"finalCourseId",2))));
    }
    @ParameterizedTest @ValueSource(strings={"1.5","0","-1","2147483648","abc"})
    void rejectsInvalidIdsWithoutTruncating(String id) {
        assertThrows(InvalidCanonicalUpdateException.class,()->FinalGradeOps.validate(
                update(Map.of("finalScore",90),Map.of("finalStudentId",id,"finalCourseId",2))));
    }
    @Test void rejectsMissingScore() {
        assertThrows(InvalidCanonicalUpdateException.class,()->FinalGradeOps.validate(update(Map.of(),Map.of("finalGradeKey","1-2"))));
    }
    @Test void rejectsDeletionWithUsefulMessage() {
        var error=assertThrows(InvalidCanonicalUpdateException.class,()->FinalGradeOps.validate(
                new CanonicalUpdate(DELETE,"FinalGrade",Map.of(),Map.of("finalGradeKey","1-2"))));
        assertTrue(error.getMessage().contains("Deleting FinalGrade is not supported"));
    }
}
