package org.tmforum.intent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * TMF630-compatible error envelope for all controller exceptions.
 *
 * Error shape: { "@type": "Error", "code": "NNN", "reason": "...", "message": "..." }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Intent/report/spec/hub not found. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException ex) {
        return error(404, "404", "Not Found", ex.getMessage());
    }

    /** Invalid FSM transition or malformed request parameter. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return error(400, "400", "Bad Request", ex.getMessage());
    }

    /** Unknown lifecycle state — HTTP 422 per TMF convention. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleUnprocessable(IllegalStateException ex) {
        return error(422, "422", "Unprocessable Entity", ex.getMessage());
    }

    /** Malformed JSON body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(400, "400", "Bad Request", "Malformed request body: " + ex.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(500, "500", "Internal Server Error", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@type", "Error");
        body.put("code", code);
        body.put("reason", reason);
        body.put("message", message != null ? message : reason);
        return ResponseEntity.status(status).body(body);
    }
}
