package nl.utwente.thesis.vdcs.source.mysql.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the MySQL source access service. */
@SpringBootApplication
public class MysqlSacApplication {
    public static void main(String[] args) {
        SpringApplication.run(MysqlSacApplication.class, args);
    }
}
