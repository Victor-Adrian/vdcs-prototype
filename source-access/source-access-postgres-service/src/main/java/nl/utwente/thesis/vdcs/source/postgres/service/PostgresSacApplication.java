package nl.utwente.thesis.vdcs.source.postgres.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the PostgreSQL source access service. */
@SpringBootApplication
public class PostgresSacApplication {
    public static void main(String[] args) {
        SpringApplication.run(PostgresSacApplication.class, args);
    }
}
