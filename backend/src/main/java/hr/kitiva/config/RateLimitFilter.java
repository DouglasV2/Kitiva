package hr.kitiva.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Sprint 10.85 — a per-IP request-rate backstop on the plan endpoints, so one client (a runaway script, a bad
 * actor, or a traffic spike) can't saturate the Tomcat threads / CPU and take the instance down for everyone.
 * {@code /api/plans/generate-fast} is unauthenticated and ungated, so it is the most exposed; this covers all of
 * {@code /api/plans/*}.
 *
 * <p>It is a coarse safety limit, NOT a business limit (per-tier AI allowances live in {@code AiUsageTracker}).
 * Fixed window per IP, in-memory (per instance — pair it with the host/CDN limiter for multi-instance). Runs
 * early so a rejected request does no work, and stamps the 429 with the CORS header so the browser can read it
 * regardless of filter ordering. The bucket map is pruned on a schedule so distinct IPs can't grow it without
 * bound.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    /**
     * The endpoints worth guarding: the ones that do real work per request. Nail generation runs the
     * parser, the resolver, the diagram and the kit assembler; the makeup ones walk the catalog. Reads of
     * the look list are cheap and stay unguarded.
     */
    private static final String[] GUARDED_PREFIXES = { "/api/nail/", "/api/makeup/" };

    private static boolean isGuarded(String uri) {
        for (String prefix : GUARDED_PREFIXES) {
            if (uri.startsWith(prefix)) return true;
        }
        return false;
    }
    private static final int HARD_MAP_CAP = 200_000;

    private final boolean enabled;
    private final int maxRequests;
    private final long windowMs;
    // Sprint 10.168: number of trusted reverse-proxy hops between the app and the public internet (Railway/most
    // PaaS = 1). Used to pick the real client from X-Forwarded-For without trusting client-supplied entries.
    private final int trustedProxyCount;
    private final Set<String> allowedOrigins;
    private final ConcurrentHashMap<String, Window> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${budgetspace.rate-limit.enabled:true}") boolean enabled,
            @Value("${budgetspace.rate-limit.requests-per-window:60}") int maxRequests,
            @Value("${budgetspace.rate-limit.window-seconds:10}") long windowSeconds,
            @Value("${budgetspace.trusted-proxy-count:1}") int trustedProxyCount,
            @Value("${app.cors.allowed-origins:http://localhost:5180}") String allowedOrigins) {
        this.enabled = enabled;
        this.maxRequests = Math.max(1, maxRequests);
        this.windowMs = Math.max(1, windowSeconds) * 1000L;
        this.trustedProxyCount = Math.max(0, trustedProxyCount);
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only the (expensive, partly unauthenticated) plan endpoints. Never the CORS preflight (OPTIONS) — the
        // browser must get its preflight answer before it can even send the real POST.
        String uri = request.getRequestURI();
        return !enabled
                || !"POST".equalsIgnoreCase(request.getMethod())
                || uri == null
                || !isGuarded(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!allow(clientIp(request))) {
            log.debug("Rate limit hit on {} from {}", request.getRequestURI(), clientIp(request));
            stampCors(request, response);
            response.setStatus(429); // 429 Too Many Requests
            response.setHeader("Retry-After", String.valueOf(Math.max(1, windowMs / 1000)));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Previše zahtjeva — pričekaj nekoliko sekundi.\",\"code\":\"RATE_LIMITED\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Fixed-window counter. ConcurrentHashMap.compute applies the remap atomically per key, so the ++ is safe. */
    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        long windowStart = now - (now % windowMs);
        Window window = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.start != windowStart) {
                return new Window(windowStart);
            }
            existing.count++;
            return existing;
        });
        return window.count <= maxRequests;
    }

    // Sprint 10.168: the real client, resilient to client-supplied X-Forwarded-For spoofing (see ClientIp).
    private String clientIp(HttpServletRequest request) {
        return ClientIp.resolve(request, trustedProxyCount);
    }

    // Echo the CORS allow-origin onto the 429 so the browser surfaces the real status, not an opaque network error.
    private void stampCors(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin != null && allowedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }
    }

    /** Drop idle windows so a flood of distinct IPs can't grow the map without bound. */
    @Scheduled(fixedDelayString = "${budgetspace.rate-limit.cleanup-ms:300000}")
    void pruneIdleBuckets() {
        long cutoff = System.currentTimeMillis() - (2 * windowMs);
        buckets.entrySet().removeIf(entry -> entry.getValue().start < cutoff);
        if (buckets.size() > HARD_MAP_CAP) {
            log.warn("Rate-limit bucket map exceeded {} entries — clearing as a backstop.", HARD_MAP_CAP);
            buckets.clear();
        }
    }

    // Visible for tests.
    int activeBucketCount() {
        return buckets.size();
    }

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
            this.count = 1;
        }
    }
}
