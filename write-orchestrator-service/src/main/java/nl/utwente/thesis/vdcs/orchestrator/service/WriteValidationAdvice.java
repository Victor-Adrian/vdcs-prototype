package nl.utwente.thesis.vdcs.orchestrator.service;

import nl.utwente.thesis.vdcs.orchestrator.model.InvalidCanonicalUpdateException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Return actionable validation errors through the access interface to the user. */
@RestControllerAdvice(assignableTypes = WriteController.class)
public class WriteValidationAdvice {
    @ExceptionHandler(InvalidCanonicalUpdateException.class)
    public ResponseEntity<String> invalidUpdate(InvalidCanonicalUpdateException error) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(error.getMessage());
    }
}
