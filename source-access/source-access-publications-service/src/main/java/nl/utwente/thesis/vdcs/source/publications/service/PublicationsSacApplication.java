package nl.utwente.thesis.vdcs.source.publications.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/** Starts the source access service for the RDF publications repository. */
@SpringBootApplication
public class PublicationsSacApplication {

    public static void main(String[] args) {
        SpringApplication.run(PublicationsSacApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
