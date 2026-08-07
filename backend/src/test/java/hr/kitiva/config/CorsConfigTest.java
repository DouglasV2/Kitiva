package hr.kitiva.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 10.98 (security) — drives the real {@link CorsFilter} with preflight requests to prove the cross-origin
 * policy is an explicit allow-list, not an open door: a configured origin is echoed back (with credentials), and an
 * unlisted origin is rejected (403, no Access-Control-Allow-Origin). Because credentials are allowed, the response
 * must always name a SPECIFIC origin — never the "*" wildcard (which, combined with credentials, is a CORS hole the
 * browser also refuses).
 */
class CorsConfigTest {

    private static MockHttpServletRequest preflight(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/plans/generate");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        return request;
    }

    @Test
    void allowsAConfiguredOriginWithCredentialsAndNeverAWildcard() throws Exception {
        CorsFilter filter = new CorsConfig().corsFilter("http://localhost:5180, https://app.budgetspace.ai");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(preflight("https://app.budgetspace.ai"), response, new MockFilterChain());

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://app.budgetspace.ai");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void rejectsAnUnlistedOrigin() throws Exception {
        CorsFilter filter = new CorsConfig().corsFilter("https://app.budgetspace.ai");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(preflight("https://evil.example.com"), response, chain);

        // Rejected preflight: 403, no allow-origin echoed, and it never reaches the app.
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(chain.getRequest()).as("a rejected preflight is short-circuited").isNull();
    }
}
