package nl.utwente.thesis.vdcs.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.utwente.thesis.vdcs.metadata.client.SourceCapabilityCatalogue;
import nl.utwente.thesis.vdcs.orchestrator.*;
import nl.utwente.thesis.vdcs.orchestrator.service.provenance.WriteProvenanceRecorder;
import nl.utwente.thesis.vdcs.orchestrator.service.sparql.SparqlUpdateTranslator;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

class WriteValidationHttpTest {
    MockMvc http; TransactionCoordinator coordinator;
    @BeforeEach void setup() {
        coordinator=mock(TransactionCoordinator.class);
        var orchestrator=new WriteOrchestrator(new UpdateRouter(new SourceCapabilityCatalogue()),coordinator);
        var controller=new WriteController(orchestrator,new SparqlUpdateTranslator(null),new ObjectMapper(),mock(WriteProvenanceRecorder.class));
        http=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new WriteValidationAdvice()).build();
    }
    @Test void jsonDeleteReturnsHelpful400() throws Exception {
        http.perform(post("/orchestrator/write").contentType("application/json")
                .content("{\"operation\":\"DELETE\",\"entityType\":\"FinalGrade\",\"filter\":{\"finalGradeKey\":\"1-2\"}}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Deleting FinalGrade is not supported")));
        verifyNoInteractions(coordinator);
    }
    @Test void jsonUnsupportedFilterReturnsHelpful400() throws Exception {
        http.perform(post("/orchestrator/write").contentType("application/json")
                .content("{\"operation\":\"UPDATE\",\"entityType\":\"FinalGrade\",\"payload\":{\"finalScore\":90},\"filter\":{\"finalGradeKey\":\"1-2\",\"finalScore\":80}}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Unsupported FinalGrade filter field 'finalScore'")));
        verifyNoInteractions(coordinator);
    }
    @Test void laterSparqlDeleteRejectsEntireRequestWithHelpful400() throws Exception {
        String request="""
            PREFIX : <https://thesis.utwente.nl/vdcs/ontology/university#>
            INSERT DATA { <https://thesis.utwente.nl/vdcs/resource/grade/999> :score 90 . };
            DELETE DATA { <https://thesis.utwente.nl/vdcs/resource/final-grade/1-2> :finalScore 80 . }
            """;
        http.perform(post("/orchestrator/write").contentType("application/sparql-update").content(request))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Deleting FinalGrade is not supported")));
        verifyNoInteractions(coordinator);
    }
}
