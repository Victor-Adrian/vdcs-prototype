package nl.utwente.thesis.vdcs.adapter.mongo.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;

/** Starts the MongoDB adapter and its H2 canonical projection. */
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
public class CanonicalAdapterApplication {
    public static void main(String[] args) {
        SpringApplication.run(CanonicalAdapterApplication.class, args);
    }
}
