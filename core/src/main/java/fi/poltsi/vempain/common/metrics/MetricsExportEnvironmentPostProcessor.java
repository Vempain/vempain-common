package fi.poltsi.vempain.common.metrics;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns the deployment-facing {@code vempain.metrics.*} and {@code vempain.tracing.*} properties (set from the {@code VEMPAIN_METRICS_*} and
 * {@code VEMPAIN_TRACING_*} environment variables) into the Spring Boot OTLP export properties. Every backend pushes its meters over OTLP/HTTP to the
 * Prometheus receiver of the monitoring environment and its spans to the Tempo receiver next to it; both sit behind the same basic authentication and
 * self-signed certificate. The host, port and path come from {@code vempain.metrics.url} / {@code vempain.tracing.url}, the credentials become the
 * {@code Authorization} header and the certificate becomes a PEM trust store bundle. Nothing is configured while both {@code vempain.metrics.enabled} and
 * {@code vempain.tracing.enabled} are false, so local runs and tests never try to export.
 * <p>
 * Registered through {@code META-INF/spring.factories} under {@code org.springframework.boot.EnvironmentPostProcessor} (the Boot 4 interface; the one in
 * {@code org.springframework.boot.env} is deprecated for removal); the hosting backends only declare the properties.
 */
public class MetricsExportEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "vempainMetricsExport";
    static final String SSL_BUNDLE_NAME = "vempain-metrics";

    private static final String EXPORT_PREFIX = "management.otlp.metrics.export.";
    private static final String TRACING_PREFIX = "management.opentelemetry.tracing.export.otlp.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var metrics = environment.getProperty("vempain.metrics.enabled", Boolean.class, false);
        var tracing = environment.getProperty("vempain.tracing.enabled", Boolean.class, false);
        if (!metrics
            && !tracing) {
            return;
        }

        environment.getPropertySources()
            .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, exportProperties(environment, metrics, tracing)));
    }

    /**
     * The metrics push ({@code management.otlp.metrics.export.*}) and the trace push ({@code management.opentelemetry.tracing.export.otlp.*}) share the
     * credentials and the certificate: Tempo sits behind the same basic auth and self-signed certificate as the Prometheus receiver.
     */
    static Map<String, Object> exportProperties(ConfigurableEnvironment environment, boolean metrics, boolean tracing) {
        var properties = new LinkedHashMap<String, Object>();

        var username = environment.getProperty("vempain.metrics.username", "");
        var authorization = username.isBlank() ? null : basicAuthorization(username, environment.getProperty("vempain.metrics.password", ""));
        var certificate = environment.getProperty("vempain.metrics.certificate", "");
        if (!certificate.isBlank()) {
            properties.put("spring.ssl.bundle.pem." + SSL_BUNDLE_NAME + ".truststore.certificate", certificateLocation(certificate.trim()));
        }

        if (metrics) {
            properties.put(EXPORT_PREFIX + "enabled", true);
            var url = environment.getProperty("vempain.metrics.url", "");
            if (!url.isBlank()) {
                properties.put(EXPORT_PREFIX + "url", url.trim());
            }
            var step = environment.getProperty("vempain.metrics.step", "");
            if (!step.isBlank()) {
                properties.put(EXPORT_PREFIX + "step", step.trim());
            }
            if (authorization != null) {
                properties.put(EXPORT_PREFIX + "headers.Authorization", authorization);
            }
            if (!certificate.isBlank()) {
                properties.put(EXPORT_PREFIX + "ssl.bundle", SSL_BUNDLE_NAME);
            }
        }

        if (tracing) {
            properties.put("management.tracing.enabled", true);
            var sampling = environment.getProperty("vempain.tracing.sampling", "");
            if (!sampling.isBlank()) {
                properties.put("management.tracing.sampling.probability", sampling.trim());
            }
            var url = environment.getProperty("vempain.tracing.url", "");
            if (!url.isBlank()) {
                properties.put(TRACING_PREFIX + "endpoint", url.trim());
            }
            if (authorization != null) {
                properties.put(TRACING_PREFIX + "headers.Authorization", authorization);
            }
            if (!certificate.isBlank()) {
                properties.put(TRACING_PREFIX + "ssl.bundle", SSL_BUNDLE_NAME);
            }
        }

        return properties;
    }

    static String basicAuthorization(String username, String password) {
        var credentials = username + ":" + password;
        return "Basic "
                + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A bare path is a file; a value with a scheme ({@code file:}, {@code classpath:}, ...) or PEM content is passed on as it is.
     */
    static String certificateLocation(String certificate) {
        if (certificate.contains("-----BEGIN")
            || certificate.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return certificate;
        }
        return "file:" + certificate;
    }

    @Override
    public int getOrder() {
        // After the configuration files have been loaded, so that the vempain.metrics.* values are known
        return Ordered.LOWEST_PRECEDENCE;
    }
}
