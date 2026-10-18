package nl.utwente.thesis.vdcs.metadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the metadata service for sources, semantic artefacts, and provenance. */
@SpringBootApplication
public class MetadataServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MetadataServiceApplication.class, args);
    }
}
