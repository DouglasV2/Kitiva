package hr.kitiva.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sprint 10.125 (security hardening, from the pre-launch probe): cap the request body size on the API.
 *
 * <p>The per-IP {@link RateLimitFilter} bounds request COUNT, not body SIZE, so a single oversized body
 * (the probe sent 7–28&nbsp;MB) could be buffered/parsed before any cap. Legitimate plan requests are tiny
 * (the prompt is truncated to 4000 chars and retailer lists have &lt;10 entries), so anything over a few
 * hundred KB is abusive — reject it with 413 (by {@code Content-Length}) before the body is read.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** 512 KB — far above any legitimate plan request, far below a memory/CPU concern. */
    private static final long MAX_BODY_BYTES = 512L * 1024L;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/") && request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(413); // Payload Too Large
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Zahtjev je prevelik.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
