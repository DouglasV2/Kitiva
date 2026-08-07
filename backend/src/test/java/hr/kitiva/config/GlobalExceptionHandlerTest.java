package hr.kitiva.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 10.98 (security) — the global error mapping. The headline security property: an unexpected exception's real
 * message (which may carry internals/secrets) must NEVER reach the client — only a generic 500. Also locks in that
 * unmapped paths return a quiet 404 (so scanner traffic doesn't become 500s / ERROR-log noise — Sprint 10.97), and the
 * expected client errors map to their proper 4xx.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedErrorReturnsAGeneric500ThatLeaksNoInternals() {
        ResponseEntity<Map<String, String>> response = handler.handleUnexpected(
                new RuntimeException("NPE in SecretVault: db password=hunter2 at jdbc://prod"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String body = response.getBody().get("error");
        assertThat(body).doesNotContain("hunter2").doesNotContain("SecretVault").doesNotContain("jdbc");
        assertThat(body).isEqualTo("Dogodila se neočekivana greška. Pokušaj ponovno za koju minutu.");
    }

    @Test
    void unmappedPathReturns404NotAScanNoise500() {
        ResponseEntity<Map<String, String>> response =
                handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "/wp-login.php"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void badInputReturns400WithTheUserFacingMessage() {
        ResponseEntity<Map<String, String>> response =
                handler.handleBadRequest(new IllegalArgumentException("Budžet nije ispravan"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("Budžet nije ispravan");
    }

    // Sprint 10.106: a bad client request (malformed/wrong-typed/missing param) must be a 400 — not a 500 that
    // also fires an ERROR-level alert. Bots and typos hit these constantly once live.
    @Test
    void malformedOrWrongTypedRequestReturns400NotA500() {
        ResponseEntity<Map<String, String>> response = handler.handleBadClientRequest(
                new org.springframework.web.bind.MissingServletRequestParameterException("budget", "int"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void wrongHttpMethodReturns405NotA500() throws Exception {
        ResponseEntity<Map<String, String>> response = handler.handleMethodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("GET"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
