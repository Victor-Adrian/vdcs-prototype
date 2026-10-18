package nl.utwente.thesis.vdcs.source.loans.service;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Exposes health and JDBC connection details for the loans source. */
@RestController
@RequestMapping("/sac")
public class SacController {

    private final LoansH2Server h2;

    public SacController(LoansH2Server h2) { this.h2 = h2; }

    @GetMapping("/connection")
    public Map<String, Object> connection() {
        return Map.of(
            "sourceId",    "library-loans",
            "engine",      "h2",
            "jdbcUrl",     h2.jdbcUrl(),
            "username",    "sa",
            "password",    "",
            "driverClass", "org.h2.Driver");
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "sourceId", "library-loans");
    }
}
