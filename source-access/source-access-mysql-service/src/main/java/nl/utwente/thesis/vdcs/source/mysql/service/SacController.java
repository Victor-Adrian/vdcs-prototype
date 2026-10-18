package nl.utwente.thesis.vdcs.source.mysql.service;

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

/** REST surface for the MySQL Source Access Service. Mirrors PG SAC. */
@RestController
@RequestMapping("/sac")
public class SacController {

    private static final Logger log = LoggerFactory.getLogger(SacController.class);

    private final MysqlWriteService writeService;

    public SacController(MysqlWriteService writeService) {
        this.writeService = writeService;
    }

    @GetMapping("/connection")
    public Map<String, Object> connection() {
        return writeService.connectionDetails();
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
        return Map.of("status", "UP", "sourceId", "academic-records");
    }
}
