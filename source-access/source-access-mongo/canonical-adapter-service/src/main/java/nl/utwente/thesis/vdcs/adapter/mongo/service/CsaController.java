package nl.utwente.thesis.vdcs.adapter.mongo.service;

import nl.utwente.thesis.vdcs.orchestrator.executor.CompletedOperation;
import nl.utwente.thesis.vdcs.orchestrator.model.CanonicalUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Exposes projection connection, refresh, write, compensation, and health endpoints. */
@RestController
@RequestMapping("/csa")
public class CsaController {

    private static final Logger log = LoggerFactory.getLogger(CsaController.class);

    private final H2TcpServer       h2;
    private final ProjectionService projection;
    private final MongoWriteService writeService;

    public CsaController(H2TcpServer h2,
                         ProjectionService projection,
                         MongoWriteService writeService) {
        this.h2           = h2;
        this.projection   = projection;
        this.writeService = writeService;
    }

    @GetMapping("/connection")
    public Map<String, Object> connection() {
        return Map.of(
                "sourceId", "student-activities",
                "engine",   "h2",   // What the mediator sees
                "jdbcUrl",  h2.jdbcUrl(),
                "username", h2.user(),
                "password", h2.password(),
                "driverClass", "org.h2.Driver");
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() throws Exception {
        log.info("CSA refresh triggered");
        projection.refresh();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/write")
    public CompletedOperation write(@RequestBody CanonicalUpdate update) throws Exception {
        log.debug("Received canonical update: {}", update);
        return writeService.apply(update);
    }

    @PostMapping("/compensate")
    public ResponseEntity<Void> compensate(@RequestBody CompletedOperation handle) throws Exception {
        log.debug("Received compensation request: {}", handle);
        writeService.compensate(handle);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "sourceId", "student-activities");
    }
}
