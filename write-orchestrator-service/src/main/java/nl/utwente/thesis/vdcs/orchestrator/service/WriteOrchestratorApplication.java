package nl.utwente.thesis.vdcs.orchestrator.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;

/** Starts the service that translates, routes, and coordinates federated writes. */
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
public class WriteOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(WriteOrchestratorApplication.class, args);
    }
}
