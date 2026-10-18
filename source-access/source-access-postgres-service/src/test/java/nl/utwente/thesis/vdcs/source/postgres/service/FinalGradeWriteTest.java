package nl.utwente.thesis.vdcs.source.postgres.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import nl.utwente.thesis.vdcs.source.postgres.StudentRegistryClient;
import nl.utwente.thesis.vdcs.orchestrator.model.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate.OperationType.*;

class FinalGradeWriteTest {
    PostgresWriteService service;
    Connection db;
    @BeforeEach void setup() throws Exception {
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL";
        db=DriverManager.getConnection(url,"sa","");
        sql("CREATE TABLE enrollments(id INT PRIMARY KEY,student_id INT,course_id INT,final_grade DECIMAL(5,2))");
        service=new PostgresWriteService();
        ReflectionTestUtils.setField(service,"client",new StudentRegistryClient(url,"sa",""));
    }
    @AfterEach void close() throws Exception {db.close();}
    void sql(String text) throws Exception {try(var stmt=db.createStatement()){stmt.execute(text);}}
    String score(int id) throws Exception {
        try(var stmt=db.createStatement();var rs=stmt.executeQuery("SELECT final_grade FROM enrollments WHERE id="+id)) {
            assertTrue(rs.next());return rs.getString(1);
        }
    }
    CanonicalUpdate update(CanonicalUpdate.OperationType op) {
        return new CanonicalUpdate(op,"FinalGrade",Map.of("finalScore",90),Map.of("finalStudentId",1,"finalCourseId",2));
    }
    @ParameterizedTest @EnumSource(value=CanonicalUpdate.OperationType.class,names={"INSERT","UPDATE"})
    void ambiguousPairLeavesEveryEnrollmentUnchanged(CanonicalUpdate.OperationType op) throws Exception {
        sql("INSERT INTO enrollments VALUES(10,1,2,70),(11,1,2,80)");
        assertTrue(assertThrows(IllegalStateException.class,()->service.apply(update(op))).getMessage().contains("Ambiguous"));
        assertEquals("70.00",score(10));assertEquals("80.00",score(11));
    }
    @ParameterizedTest @EnumSource(value=CanonicalUpdate.OperationType.class,names={"INSERT","UPDATE"})
    void uniqueEnrollmentCanBeChangedAndRestored(CanonicalUpdate.OperationType op) throws Exception {
        sql("INSERT INTO enrollments VALUES(10,1,2,70)");
        var handle=service.apply(update(op));assertEquals("90.00",score(10));
        assertEquals(10,handle.getSnapshot().get("enrollment_id"));
        service.compensate(handle);assertEquals("70.00",score(10));
    }
    @Test void compensationRestoresNullWithoutDeletingEnrollment() throws Exception {
        sql("INSERT INTO enrollments VALUES(10,1,2,NULL)");
        var handle=service.apply(update(UPDATE));assertEquals("90.00",score(10));
        service.compensate(handle);assertNull(score(10));
    }
    @Test void compensationOnlyRestoresCapturedEnrollmentEvenIfAnotherAttemptAppears() throws Exception {
        sql("INSERT INTO enrollments VALUES(10,1,2,70)");
        var handle=service.apply(update(UPDATE));
        sql("INSERT INTO enrollments VALUES(11,1,2,80)");
        service.compensate(handle);assertEquals("70.00",score(10));assertEquals("80.00",score(11));
    }
    @Test void missingEnrollmentRejected() {
        assertThrows(IllegalStateException.class,()->service.apply(update(UPDATE)));
    }
    @Test void directSourceCallRejectsUnsupportedFilterBeforeWriting() throws Exception {
        sql("INSERT INTO enrollments VALUES(10,1,2,70)");
        var u=new CanonicalUpdate(UPDATE,"FinalGrade",Map.of("finalScore",90),Map.of("finalGradeKey","1-2","finalScore",0));
        assertThrows(InvalidCanonicalUpdateException.class,()->service.apply(u));assertEquals("70.00",score(10));
    }
    @Test void directSourceCallRejectsDeletion() throws Exception {
        sql("INSERT INTO enrollments VALUES(10,1,2,70)");
        assertThrows(InvalidCanonicalUpdateException.class,()->service.apply(update(DELETE)));assertEquals("70.00",score(10));
    }
}
