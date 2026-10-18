package nl.utwente.thesis.vdcs.mediator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the SPARQL mediator and its Ontop-backed source repositories. */
@SpringBootApplication
public class SemanticMediatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticMediatorApplication.class, args);
    }
}
