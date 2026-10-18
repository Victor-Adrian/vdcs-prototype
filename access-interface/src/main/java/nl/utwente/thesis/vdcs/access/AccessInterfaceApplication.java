package nl.utwente.thesis.vdcs.access;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP entry point for queries and updates. Reads go to the semantic
 * mediator, while writes go to the write orchestrator.
 */
@SpringBootApplication
public class AccessInterfaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessInterfaceApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
