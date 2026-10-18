package nl.utwente.thesis.vdcs.secret;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the credential service used by source access components. */
@SpringBootApplication
public class SecretStoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecretStoreApplication.class, args);
    }
}
