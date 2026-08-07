package hr.kitiva.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Sprint 10.9 — single place that logs failures and returns a clean, user-facing JSON error instead
 * of a raw stack trace. Cases:
 * <ul>
 *       → {@code 404}, logged quietly — an expected client situation, not a server fault.</li>
 *   <li>{@link NoResourceFoundException} (an unmapped path / missing static resource — mostly bots and scanners)
 *       → {@code 404}, logged at debug so scan traffic never becomes a 500 or an ERROR-level log event.</li>
 *   <li>{@link IllegalArgumentException} (bad input, e.g. a malformed replace request) → {@code 400}
 *       with the original message (these are already written in Croatian for the user).</li>
 *   <li>anything else → {@code 500} with a generic message; the real cause is logged at error level
 *       so an operator can see it without leaking internals to the client.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException exception) {
        // An unmapped path / missing static resource — mostly bots and vulnerability scanners (/wp-login.php, /.env).
        // Return a proper 404 and log it at DEBUG, so scan noise never becomes a 500 OR an ERROR-level log event
        // that would drown out real failures.
        log.debug("No resource for request: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Nije pronađeno."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        log.warn("Bad request rejected: {}", exception.getMessage());
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Zahtjev nije ispravan."
                : exception.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    // Sprint 10.106 (security hardening): Spring MVC framework errors for a BAD CLIENT REQUEST — malformed/
    // unreadable JSON, a wrong-typed or missing parameter — are the client's fault, not ours. Map them to a clean
    // 400 logged at DEBUG, so routine bot/scanner/typo traffic never becomes a 500 OR a noisy ERROR-level log.
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, String>> handleBadClientRequest(Exception exception) {
        log.debug("Bad request rejected: {}", exception.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Map.of("error", "Zahtjev nije ispravan."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        log.debug("Method not allowed: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("error", "Metoda nije dopuštena."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        log.debug("Unsupported media type: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("error", "Nepodržan tip sadržaja."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
        log.error("Unhandled error while processing request", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Dogodila se neočekivana greška. Pokušaj ponovno za koju minutu."));
    }
}
