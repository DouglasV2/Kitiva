package hr.kitiva.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Sprint 10.5 — keeps the dev/collector/import/admin endpoints off the public surface in
 * production. These endpoints can mutate the catalog or trigger collection, so a public user must
 * not be able to reach them once the app is launched.
 *
 * <p>Controlled by {@code budgetspace.admin-endpoints.enabled} (env
 * {@code BUDGETSPACE_ADMIN_ENDPOINTS_ENABLED}). Enabled by default for local/dev; the {@code prod}
 * profile sets it to {@code false}. When disabled, the guarded paths answer {@code 404} so their
 * existence is not advertised. The public read endpoints ({@code GET /api/products},
 * {@code /api/plans/*}, {@code /api/saved-plans/*}, {@code /api/events/*}) are never blocked.</p>
 */
@Component
public class AdminEndpointGuardFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminEndpointGuardFilter.class);

    /** Path prefixes that must not be public in production. */
    private static final List<String> GUARDED_PREFIXES = List.of(
            "/api/products/import",
            "/api/products/collect",
            "/api/products/catalog-health",
            "/api/products/catalog-audit"
    );

    private final boolean adminEnabled;

    // Sprint 10.135: the @Value fallback is FALSE too (fail-safe) — if the property is missing entirely, the guard
    // blocks rather than exposes. The real default lives in application.yml (also false now); dev opts in via env.
    public AdminEndpointGuardFilter(@Value("${budgetspace.admin-endpoints.enabled:false}") boolean adminEnabled) {
        this.adminEnabled = adminEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!adminEnabled && isGuarded(request.getRequestURI())) {
            log.debug("Blocked admin/collector endpoint in locked-down mode: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Endpoint nije dostupan u ovom okruženju.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isGuarded(String uri) {
        if (uri == null) return false;
        for (String prefix : GUARDED_PREFIXES) {
            if (uri.equals(prefix) || uri.startsWith(prefix)) return true;
        }
        return false;
    }
}
