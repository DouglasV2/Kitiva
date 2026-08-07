package hr.kitiva.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sprint 10.67 (security audit) — baseline security response headers on every response. Cheap, framework-agnostic
 * hardening:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — stop MIME-sniffing.</li>
 *   <li>{@code X-Frame-Options: DENY} — the app is never framed, so block clickjacking.</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — don't leak full URLs cross-site.</li>
 *   <li>{@code Strict-Transport-Security} — only when the request actually arrived over HTTPS, so local HTTP dev
 *       on :5180 is unaffected and prod (behind TLS) gets HSTS.</li>
 * </ul>
 *
 * <p>A full Content-Security-Policy is deliberately deferred: it must allow-list Google Identity Services
 * (accounts.google.com / gstatic) + the retailer image hosts, so it's tracked for the deploy checklist rather
 * than shipped half-correct (a wrong CSP would silently break sign-in or images).</p>
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(request, response);
    }
}
