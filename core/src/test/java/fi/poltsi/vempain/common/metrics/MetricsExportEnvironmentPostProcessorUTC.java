package fi.poltsi.vempain.common.metrics;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.Ordered;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The post-processor maps {@code vempain.metrics.*} to the Boot OTLP export properties, and only when the export is enabled.
 */
class MetricsExportEnvironmentPostProcessorUTC {

    private final MetricsExportEnvironmentPostProcessor processor = new MetricsExportEnvironmentPostProcessor();

    @Test
    void disabledExportLeavesTheEnvironmentAlone() {
        var environment = environment("vempain.metrics.url=https://grafana.example/prometheus/api/v1/otlp/v1/metrics");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources()
            .contains(MetricsExportEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(environment.getProperty("management.otlp.metrics.export.url")).isNull();
    }

    @Test
    void enabledExportConfiguresUrlStepCredentialsAndCertificate() {
        var environment = environment("vempain.metrics.enabled=true", "vempain.metrics.url= https://grafana.intra.example/prometheus/api/v1/otlp/v1/metrics ",
                "vempain.metrics.step=30s", "vempain.metrics.username=vempain", "vempain.metrics.password=s3cret:with:colons",
                "vempain.metrics.certificate=/run/secrets/metrics-certificate.crt");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("management.otlp.metrics.export.url")).isEqualTo("https://grafana.intra.example/prometheus/api/v1/otlp/v1/metrics");
        assertThat(environment.getProperty("management.otlp.metrics.export.step")).isEqualTo("30s");
        var expectedHeader = "Basic "
                + Base64.getEncoder()
                    .encodeToString("vempain:s3cret:with:colons".getBytes(StandardCharsets.UTF_8));
        assertThat(environment.getProperty("management.otlp.metrics.export.headers.Authorization")).isEqualTo(expectedHeader);
        assertThat(environment.getProperty("spring.ssl.bundle.pem.vempain-metrics.truststore.certificate"))
            .isEqualTo("file:/run/secrets/metrics-certificate.crt");
        assertThat(environment.getProperty("management.otlp.metrics.export.ssl.bundle")).isEqualTo("vempain-metrics");
        // The generated source wins over the defaults of the configuration files
        assertThat(environment.getPropertySources()
            .iterator()
            .next()
            .getName()).isEqualTo(MetricsExportEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }

    @Test
    void optionalValuesAreOnlySetWhenGiven() {
        var environment = environment("vempain.metrics.enabled=true", "vempain.metrics.url=", "vempain.metrics.step=", "vempain.metrics.username=",
                "vempain.metrics.password=ignored", "vempain.metrics.certificate=");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("management.otlp.metrics.export.url")).isNull();
        assertThat(environment.getProperty("management.otlp.metrics.export.step")).isNull();
        assertThat(environment.getProperty("management.otlp.metrics.export.headers.Authorization")).as("no username, no header")
            .isNull();
        assertThat(environment.getProperty("management.otlp.metrics.export.ssl.bundle")).isNull();
    }

    @Test
    void tracingAloneConfiguresTheTraceExportWithTheSharedCredentialsAndCertificate() {
        var environment = environment("vempain.tracing.enabled=true", "vempain.tracing.url=https://grafana.intra.example/tempo/v1/traces",
                "vempain.tracing.sampling=0.25", "vempain.metrics.username=vempain", "vempain.metrics.password=pw",
                "vempain.metrics.certificate=/run/secrets/metrics-certificate.crt");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.tracing.export.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.25");
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")).isEqualTo("https://grafana.intra.example/tempo/v1/traces");
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.headers.Authorization"))
            .isEqualTo(MetricsExportEnvironmentPostProcessor.basicAuthorization("vempain", "pw"));
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.ssl.bundle")).isEqualTo("vempain-metrics");
        assertThat(environment.getProperty("spring.ssl.bundle.pem.vempain-metrics.truststore.certificate"))
            .isEqualTo("file:/run/secrets/metrics-certificate.crt");
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).as("metrics stay off")
            .isNull();
        assertThat(environment.getProperty("management.otlp.metrics.export.ssl.bundle")).isNull();
    }

    @Test
    void metricsAloneLeaveTracingAlone() {
        var environment = environment("vempain.metrics.enabled=true", "vempain.metrics.url=https://grafana.intra.example/prometheus/api/v1/otlp/v1/metrics",
                "vempain.tracing.url=https://grafana.intra.example/tempo/v1/traces");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("management.tracing.export.enabled")).isNull();
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")).isNull();
    }

    @Test
    void certificateLocationsKeepTheirSchemeAndPemContentIsPassedThrough() {
        assertThat(MetricsExportEnvironmentPostProcessor.certificateLocation("/etc/certs/ca.pem")).isEqualTo("file:/etc/certs/ca.pem");
        assertThat(MetricsExportEnvironmentPostProcessor.certificateLocation("certs/ca.pem")).isEqualTo("file:certs/ca.pem");
        assertThat(MetricsExportEnvironmentPostProcessor.certificateLocation("file:/etc/certs/ca.pem")).isEqualTo("file:/etc/certs/ca.pem");
        assertThat(MetricsExportEnvironmentPostProcessor.certificateLocation("classpath:ca.pem")).isEqualTo("classpath:ca.pem");
        assertThat(MetricsExportEnvironmentPostProcessor.certificateLocation("-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----"))
            .startsWith("-----BEGIN");
    }

    @Test
    void runsAfterTheConfigurationFilesHaveBeenLoaded() {
        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    private static StandardEnvironment environment(String... pairs) {
        var environment = new StandardEnvironment();
        TestPropertyValues.of(pairs)
            .applyTo(environment);
        return environment;
    }
}
