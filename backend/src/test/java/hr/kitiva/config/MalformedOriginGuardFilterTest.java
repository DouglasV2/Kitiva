package hr.kitiva.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 10.157 (pre-launch security probe) — a malformed {@code Origin} header (non-numeric port) used to make
 * Spring's {@code CorsFilter} throw an {@link IllegalArgumentException} that escaped the exception handler and
 * surfaced as a {@code 500}. This guard turns that into a clean {@code 403} before {@code CorsFilter} runs, while
 * leaving well-formed and Origin-less requests completely untouched.
 */
class MalformedOriginGuardFilterTest {

    private final MalformedOriginGuardFilter filter = new MalformedOriginGuardFilter();

    private static MockHttpServletRequest get(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        // A real cross-origin request has a request URL to compare against; give one so isCorsRequest reaches the
        // port parse rather than short-circuiting.
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8090);
        return request;
    }

    @Test
    void rejectsAMalformedOriginWith403AndDoesNotContinueTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get("http://localhost:5180.evil.com"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("a malformed Origin is short-circuited, never reaching CorsFilter/MVC").isNull();
    }

    @Test
    void letsAWellFormedCrossOriginRequestThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get("http://localhost:5180"), response, chain);

        // The guard does not judge whether the origin is allowed (that is CorsFilter's job) — it only blocks
        // *unparseable* ones. A well-formed origin flows on.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("a well-formed origin continues down the chain").isNotNull();
    }

    @Test
    void ignoresRequestsWithNoOriginHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("same-origin / no-Origin requests are untouched").isNotNull();
    }
}
