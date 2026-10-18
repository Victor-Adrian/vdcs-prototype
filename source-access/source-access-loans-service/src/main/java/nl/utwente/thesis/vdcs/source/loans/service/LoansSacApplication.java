package nl.utwente.thesis.vdcs.source.loans.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;

/** Runs the read-only Library Loans H2 service. */
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
public class LoansSacApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoansSacApplication.class, args);
    }
}
