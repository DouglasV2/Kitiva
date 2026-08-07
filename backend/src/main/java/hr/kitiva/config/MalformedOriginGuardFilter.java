package hr.kitiva.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sprint 10.157 (pre-launch security probe) — reject a malformed {@code Origin} header with a clean 403
 * instead of letting it bubble up as a 500.
 *
 * <p>Spring's {@link org.springframework.web.filter.CorsFilter CorsFilter} parses the request's Origin to
 * decide if it is a cross-origin request ({@link CorsUtils#isCorsRequest}). An Origin with a non-numeric
 * port segment (e.g. {@code http://localhost:5180.evil.com}) makes {@code getPort()} throw an
 * {@link IllegalStateException} ("The port must be an integer") <em>inside</em> the filter chain, before
 * Spring MVC — so the {@code @RestControllerAdvice} never sees it and Tomcat answers {@code 500}. Harmless
 * (no data leak — the
 * error valve is silenced and stack traces are off), but it is the wrong status and it fills the logs with
 * noise every time a scanner sends a junk Origin.</p>
 *
 * <p>This guard runs the <em>same</em> parse defensively one step ahead of {@code CorsFilter}: if it throws,
 * the Origin is malformed and cannot match our all-list anyway, so we short-circuit with {@code 403}. Calling
 * {@code isCorsRequest} twice (here and again in {@code CorsFilter} on the happy path) is a cheap, idempotent
 * header read that no-ops entirely when there is no Origin header.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20) // after RequestSizeLimitFilter (+10), before CorsFilter
public class MalformedOriginGuardFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            CorsUtils.isCorsRequest(request); // parses the Origin's port; throws on a malformed one
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // A non-numeric port throws IllegalStateException ("The port must be an integer"); other malformed
            // URIs throw IllegalArgumentException. Both mean the Origin is unparseable -> reject cleanly.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Invalid Origin header.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
