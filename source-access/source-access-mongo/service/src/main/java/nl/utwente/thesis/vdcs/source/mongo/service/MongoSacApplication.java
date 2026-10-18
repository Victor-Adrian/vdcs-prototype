package nl.utwente.thesis.vdcs.source.mongo.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/** Starts the MongoDB source access facade in front of the canonical adapter. */
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
public class MongoSacApplication {
    public static void main(String[] args) {
        SpringApplication.run(MongoSacApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
