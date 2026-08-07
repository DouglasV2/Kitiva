package hr.kitiva.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 10.85 — the per-IP rate-limit backstop on /api/nail/* and /api/makeup/*: allows a burst up to the limit, 429s beyond it,
 * counts per IP, and never touches other paths/methods or the CORS preflight.
 */
class RateLimitFilterTest {

    private static MockHttpServletRequest post(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/nail/generate");
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void allowsUpToTheLimitThenReturns429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 3, 10, 1, "http://localhost:5180");

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(post("1.2.3.4"), response, chain);
            assertThat(chain.getRequest()).as("request %s passes through", i).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        // The 4th request in the same window is throttled.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(post("1.2.3.4"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(chain.getRequest()).as("blocked request does not reach the chain").isNull();
    }

    @Test
    void limitsAreCountedPerIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 10, 1, "http://localhost:5180");
        // IP A spends its single token.
        filter.doFilter(post("10.0.0.1"), new MockHttpServletResponse(), new MockFilterChain());

        // IP B still has its own allowance.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(post("10.0.0.2"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotLimitOtherPathsOrMethods() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 10, 1, "http://localhost:5180");

        // A GET to a plan path is not a write and is not limited.
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/nail/generate");
        get.setRemoteAddr("9.9.9.9");
        MockFilterChain getChain = new MockFilterChain();
        filter.doFilter(get, new MockHttpServletResponse(), getChain);
        assertThat(getChain.getRequest()).isNotNull();

        // A POST to a non-plan path is not limited.
        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/health");
        other.setRemoteAddr("9.9.9.9");
        MockFilterChain otherChain = new MockFilterChain();
        filter.doFilter(other, new MockHttpServletResponse(), otherChain);
        assertThat(otherChain.getRequest()).isNotNull();

        // No buckets were created for those.
        assertThat(filter.activeBucketCount()).isZero();
    }

    @Test
    void ignoresSpoofedXffPrefixAndCountsTheProxyAppendedClient() throws Exception {
        // Sprint 10.168: with 1 trusted proxy hop, a client that prepends fake X-Forwarded-For entries can't
        // rotate its way past the per-IP limit — the real client (the entry OUR proxy appended, rightmost) counts.
        RateLimitFilter filter = new RateLimitFilter(true, 1, 10, 1, "http://localhost:5180");

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/nail/generate");
        first.setRemoteAddr("10.0.0.9"); // the proxy socket address (not the client)
        first.addHeader("X-Forwarded-For", "9.9.9.9, 1.2.3.4"); // spoofed prefix, real client appended by the proxy
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        // Same real client, but the attacker rotates the spoofed prefix → must still be throttled (keyed on 1.2.3.4).
        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/nail/generate");
        second.setRemoteAddr("10.0.0.9");
        second.addHeader("X-Forwarded-For", "8.8.8.8, 1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(second, response, chain);

        assertThat(response.getStatus()).as("rotated XFF prefix, same real client → limited").isEqualTo(429);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void disabledFilterLetsEverythingThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(false, 1, 10, 1, "http://localhost:5180");
        for (int i = 0; i < 5; i++) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(post("1.1.1.1"), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
    }
}
