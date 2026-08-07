package hr.kitiva.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class CorsConfig {
    @Bean
    CorsFilter corsFilter(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-BudgetSpace-Session is sent by the frontend on /api/plans/generate (per-session AI usage
        // limit id). It MUST be allowed or the browser's CORS preflight is rejected with no
        // Access-Control-Allow-Origin header, which blocks plan generation from the browser.
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "X-BudgetSpace-Session"));
        // Sprint 10.63: the auth session is an HttpOnly cookie, so the browser must be allowed to send it on
        // cross-origin XHR. Credentials are safe here because the allowed origins are an explicit all-list (never
        // "*"); the cookie is SameSite=Lax and only flows to these same-site dev/prod origins.
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsFilter(source);
    }
}
