package hr.kitiva.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 10.155 (security regression guard) — locks in the request-body size cap from the 10.125 pre-launch
 * probe so a future refactor can't silently drop it. The per-IP {@link RateLimitFilter} bounds request COUNT,
 * not body SIZE, so without this cap a single oversized body could be buffered/parsed before any limit applied.
 * The filter rejects an over-cap {@code /api/} body with 413 by {@code Content-Length}, BEFORE the body is read.
 */
class RequestSizeLimitFilterTest {

    /** Mirror of the production constant (512 KB). */
    private static final long LIMIT = 512L * 1024L;

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

    // setContent(byte[]) makes getContentLengthLong() report the array length (the value the filter inspects),
    // without us having to fake a Content-Length header.
    private static MockHttpServletRequest apiRequest(long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plans/generate");
        request.setContentType("application/json");
        request.setContent(new byte[(int) contentLength]);
        return request;
    }

    @Test
    void rejectsAnOversizedApiBodyWith413AndNeverReadsIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest(LIMIT + 1), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("error");
        // The body is never handed to the application — the request is short-circuited at the filter.
        assertThat(chain.getRequest()).as("an over-cap request is short-circuited").isNull();
    }

    @Test
    void allowsABodyExactlyAtTheLimit() throws Exception {
        // The cap is strictly greater-than, so a request at exactly the limit still flows through.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest(LIMIT), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("a within-cap request reaches the app").isNotNull();
    }

    @Test
    void allowsASmallApiBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(apiRequest(2_048), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsARequestWithoutAContentLength() throws Exception {
        // No Content-Length (e.g. chunked, or a GET) → getContentLengthLong() is -1, which is never over the cap.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/me"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void doesNotCapNonApiPathsEvenWhenLarge() throws Exception {
        // The cap is scoped to /api/ only — a large static asset (served elsewhere) is not this filter's concern.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
        request.setContent(new byte[(int) (LIMIT * 4)]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("a non-/api/ path is not capped here").isNotNull();
    }
}
