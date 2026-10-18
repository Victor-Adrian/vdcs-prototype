package nl.utwente.thesis.vdcs.orchestrator;

import org.junit.jupiter.api.Test;
import nl.utwente.thesis.vdcs.metadata.client.*;
import nl.utwente.thesis.vdcs.orchestrator.model.*;
import nl.utwente.thesis.vdcs.orchestrator.executor.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate.OperationType.*;

class WritePlanningTest {
    static class RecordingExecutor implements WriteExecutor {
        final String id; int applied; int compensated;
        RecordingExecutor(String id) {this.id=id;}
        public String sourceId(){return id;}
        public Set<String> supportedEntityTypes(){return Set.of("FinalGrade","Grade");}
        public CompletedOperation apply(CanonicalUpdate u){applied++; return new CompletedOperation(id,u,Map.of());}
        public void compensate(CompletedOperation op){compensated++;}
    }
    final RecordingExecutor pg=new RecordingExecutor("pg"), mysql=new RecordingExecutor("mysql");
    WriteOrchestrator orchestrator() {
        var catalogue=new SourceCapabilityCatalogue();
        for(var id:List.of("pg","mysql")) {
            var source=new SourceDescriptor(); source.setId(id);source.setEntities(List.of("FinalGrade","Grade"));
            source.setCapabilities(List.of("write"));catalogue.getSources().add(source);
        }
        List<WriteExecutor> executors=List.of(pg,mysql);
        return new WriteOrchestrator(new UpdateRouter(catalogue),new TransactionCoordinator(executors,new CompensationManager(executors)));
    }
    CanonicalUpdate valid() {return new CanonicalUpdate(UPDATE,"Grade",Map.of("score",90),Map.of("gradeId",1));}
    void assertRejectedBeforeAnyExecution(CanonicalUpdate invalid) {
        assertThrows(InvalidCanonicalUpdateException.class,()->orchestrator().executeBatch(List.of(valid(),invalid)));
        assertEquals(0,pg.applied+mysql.applied);assertEquals(0,pg.compensated+mysql.compensated);
    }
    @Test void lateDeletionRejectsWholeBatchWithoutApplyOrCompensation() {
        assertRejectedBeforeAnyExecution(new CanonicalUpdate(DELETE,"FinalGrade",Map.of(),Map.of("finalGradeKey","1-2")));
    }
    @Test void lateUnsupportedFilterRejectsWholeBatch() {
        assertRejectedBeforeAnyExecution(new CanonicalUpdate(UPDATE,"FinalGrade",Map.of("finalScore",90),
                Map.of("finalGradeKey","1-2","finalScore",80)));
    }
    @Test void lateConflictingKeyRejectsWholeBatch() {
        assertRejectedBeforeAnyExecution(new CanonicalUpdate(UPDATE,"FinalGrade",Map.of("finalScore",90,"finalGradeKey","1-3"),
                Map.of("finalGradeKey","1-2")));
    }
    @Test void singleEntryPointAlsoValidates() {
        assertThrows(InvalidCanonicalUpdateException.class,()->orchestrator().execute(
                new CanonicalUpdate(DELETE,"FinalGrade",Map.of(),Map.of("finalGradeKey","1-2"))));
        assertEquals(0,pg.applied+mysql.applied);
    }
    @Test void validFinalGradeStillReachesEveryOwner() throws Exception {
        orchestrator().execute(new CanonicalUpdate(UPDATE,"FinalGrade",Map.of("finalScore",90),Map.of("finalGradeKey","1-2")));
        assertEquals(1,pg.applied);assertEquals(1,mysql.applied);
    }
}
