package hr.kitiva.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 10.155 (security regression guard) — pins the fail-safe behaviour of the admin/collector endpoint gate.
 *
 * <p>These endpoints mutate the catalog or trigger collection ({@code /collect} is an SSRF surface), so in
 * production they must be unreachable. The guard is fail-safe: the constructor default is {@code false} (10.135),
 * so a deploy that simply forgets to set the property still blocks rather than exposes. This test locks both
 * halves in: when disabled, the guarded paths answer 404 (existence not advertised) and never reach the app;
 * when explicitly enabled (dev opt-in), they pass through. Public read endpoints are never blocked.</p>
 */
class AdminEndpointGuardFilterTest {

    private static final List<String> GUARDED = List.of(
            "/api/products/import",
            "/api/products/collect",
            "/api/products/catalog-health"
    );

    private static final List<String> PUBLIC = List.of(
            "/api/products",
            "/api/plans/generate",
            "/api/plans/generate-fast",
            "/api/saved-plans",
            "/api/events/plan-feedback",
            "/api/auth/me"
    );

    @Test
    void blocksEveryGuardedEndpointWith404WhenDisabled() throws Exception {
        AdminEndpointGuardFilter filter = new AdminEndpointGuardFilter(false);
        for (String uri : GUARDED) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(new MockHttpServletRequest("POST", uri), response, chain);

            assertThat(response.getStatus()).as(uri).isEqualTo(404);
            assertThat(chain.getRequest()).as(uri + " must be short-circuited").isNull();
        }
    }

    @Test
    void blocksSubPathsOfAGuardedPrefixWhenDisabled() throws Exception {
        // The guard matches on prefix, so a deeper collector path (e.g. /api/products/import/ikea) is blocked too.
        AdminEndpointGuardFilter filter = new AdminEndpointGuardFilter(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/products/import/ikea"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void neverBlocksPublicEndpointsWhenDisabled() throws Exception {
        AdminEndpointGuardFilter filter = new AdminEndpointGuardFilter(false);
        for (String uri : PUBLIC) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(new MockHttpServletRequest("POST", uri), response, chain);

            assertThat(chain.getRequest()).as(uri + " must pass through").isNotNull();
            assertThat(response.getStatus()).as(uri).isEqualTo(200);
        }
    }

    @Test
    void allowsGuardedEndpointsWhenExplicitlyEnabled() throws Exception {
        // Dev opt-in (BUDGETSPACE_ADMIN_ENDPOINTS_ENABLED=true): the guarded paths reach the app normally.
        AdminEndpointGuardFilter filter = new AdminEndpointGuardFilter(true);
        for (String uri : GUARDED) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(new MockHttpServletRequest("POST", uri), response, chain);

            assertThat(chain.getRequest()).as(uri + " passes through when enabled").isNotNull();
            assertThat(response.getStatus()).as(uri).isEqualTo(200);
        }
    }
}
