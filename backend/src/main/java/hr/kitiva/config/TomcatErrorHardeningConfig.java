package hr.kitiva.config;

import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sprint 10.125 (security hardening, from the pre-launch probe): suppress Tomcat's default error page.
 *
 * <p>An exception thrown inside the servlet FILTER chain (e.g. a malformed CORS {@code Origin} with a
 * non-integer port) or a URL the connector rejects BEFORE Spring dispatches (e.g. an encoded traversal
 * path) bypasses our {@code @RestControllerAdvice} JSON handler and falls through to Tomcat's
 * {@link ErrorReportValve}, which by default renders a stack trace, the filter-chain internals, and the
 * exact Tomcat version. Turning off {@code showReport} and {@code showServerInfo} returns a blank/generic
 * body instead, so no internals or version leak. App-dispatched errors are unaffected (still clean JSON
 * from {@code GlobalExceptionHandler}).
 */
@Configuration
public class TomcatErrorHardeningConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> errorPageHardening() {
        return factory -> {
            // Belt-and-suspenders: (1) set the valve CLASS so any ErrorReportValve Tomcat creates is the
            // silenced subclass, and (2) silence any already-present valve in the host pipeline.
            factory.addContextCustomizers(context -> {
                if (context.getParent() instanceof StandardHost host) {
                    host.setErrorReportValveClass(SilentErrorReportValve.class.getName());
                    for (Valve valve : host.getPipeline().getValves()) {
                        if (valve instanceof ErrorReportValve report) {
                            report.setShowReport(false);
                            report.setShowServerInfo(false);
                        }
                    }
                }
            });
        };
    }

    /** An {@link ErrorReportValve} that never renders a stack trace or the server version. */
    public static class SilentErrorReportValve extends ErrorReportValve {
        public SilentErrorReportValve() {
            setShowReport(false);
            setShowServerInfo(false);
        }
    }
}
