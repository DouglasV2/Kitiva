package hr.kitiva.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 10.98 (security) — locks in the baseline hardening headers so a future refactor can't silently drop them.
 * Asserts nosniff / DENY framing / referrer policy on every response, and that HSTS is sent ONLY over HTTPS (so
 * local HTTP dev isn't pinned to HTTPS by the browser).
 */
class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void setsTheBaselineHardeningHeadersOnEveryResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/me"), response, chain);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(chain.getRequest()).as("the request still flows through the chain").isNotNull();
    }

    @Test
    void sendsHstsOnlyOverHttps() throws Exception {
        // Plain HTTP (local dev on :5180): no HSTS, or the browser would pin localhost to HTTPS and break dev.
        MockHttpServletRequest insecure = new MockHttpServletRequest("GET", "/");
        insecure.setSecure(false);
        MockHttpServletResponse insecureResponse = new MockHttpServletResponse();
        filter.doFilter(insecure, insecureResponse, new MockFilterChain());
        assertThat(insecureResponse.getHeader("Strict-Transport-Security")).isNull();

        // HTTPS (prod behind TLS): HSTS present with a long max-age + subdomains.
        MockHttpServletRequest secure = new MockHttpServletRequest("GET", "/");
        secure.setSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        filter.doFilter(secure, secureResponse, new MockFilterChain());
        assertThat(secureResponse.getHeader("Strict-Transport-Security"))
                .contains("max-age=31536000").contains("includeSubDomains");
    }
}
