package hr.kitiva.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Sprint 10.168 — resolve the real client IP behind a known number of trusted reverse proxies, WITHOUT trusting
 * client-supplied X-Forwarded-For entries. Proxies append the address they received from, so with N trusted hops
 * the real client is the Nth entry from the right — the one OUR outermost proxy recorded. A bad actor can prepend
 * fake X-Forwarded-For values (e.g. to rotate "IPs" and dodge a per-IP limit or the per-guest AI cap), but cannot
 * forge the entry our own proxy appends. Falls back to the socket address when X-Forwarded-For is absent or shorter
 * than the hop count (direct/local requests, or a misconfigured count). Shared by RateLimitFilter + PlanController.
 */
public final class ClientIp {
    private ClientIp() {
    }

    public static String resolve(HttpServletRequest request, int trustedProxyCount) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && trustedProxyCount > 0) {
            String[] parts = forwarded.split(",");
            int idx = parts.length - trustedProxyCount;
            if (idx >= 0 && idx < parts.length) {
                String candidate = parts[idx].trim();
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
