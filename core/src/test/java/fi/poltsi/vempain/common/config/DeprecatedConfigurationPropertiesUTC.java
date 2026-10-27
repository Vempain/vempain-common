package fi.poltsi.vempain.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.poltsi.vempain.common.config.DeprecatedConfigurationProperties.Deprecation;
import fi.poltsi.vempain.common.config.DeprecatedConfigurationProperties.Finding;

/**
 * The scanner reads the deprecations Boot ships in its configuration metadata and reports every key of an application file that is, or lies under, such a
 * property, so that a backend's configuration test fails the build on a deprecated key.
 */
class DeprecatedConfigurationPropertiesUTC {

    private static final Map<String, Deprecation> DEPRECATED = Map.of("management.tracing.enabled",
            new Deprecation("management.tracing.enabled", "management.tracing.export.enabled", "error", null), "spring.http.encoding",
            new Deprecation("spring.http.encoding", "server.servlet.encoding", "error", "Replaced by server.servlet.encoding"));

    @TempDir
    Path directory;

    @Test
    void deprecatedPropertiesComeFromTheClassPathMetadata() {
        var deprecated = DeprecatedConfigurationProperties.deprecatedProperties();

        // spring-boot-jdbc has carried this one since the SQL initialisation was reworked, so it is present with every Boot 3/4 version
        assertThat(deprecated).containsKey("spring.datasource.continue-on-error");
        assertThat(deprecated.get("spring.datasource.continue-on-error")
            .replacement()).isEqualTo("spring.sql.init.continue-on-error");
        assertThat(deprecated.values()).allSatisfy(deprecation -> assertThat(deprecation.property()).isNotBlank());
    }

    @Test
    void yamlKeysAreReportedWithTheirReplacementIncludingKeysUnderADeprecatedGroup() throws IOException {
        var yaml = write("application.yaml", """
                spring:
                  application:
                    name: @project.name@
                  http:
                    encoding:
                      charset: UTF-8
                management:
                  endpoints:
                    web:
                      exposure:
                        include: [ health, info ]
                  tracing:
                    enabled: ${VEMPAIN_TRACING_ENABLED:false}
                    export:
                      enabled: true
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                management:
                  tracing:
                    enabled: true
                """);

        var findings = DeprecatedConfigurationProperties.scan(DEPRECATED, List.of(yaml));

        assertThat(findings).extracting(Finding::key)
            .containsExactly("management.tracing.enabled", "spring.http.encoding.charset");
        assertThat(findings.getFirst()
            .describe()).isEqualTo(yaml + ": management.tracing.enabled is deprecated (level error), use management.tracing.export.enabled");
        assertThat(findings.get(1)
            .describe())
            .isEqualTo(yaml
                    + ": spring.http.encoding.charset is deprecated (level error) through spring.http.encoding, use server.servlet.encoding: "
                    + "Replaced by server.servlet.encoding");
    }

    @Test
    void propertiesFilesAreScannedToo() throws IOException {
        var properties = write("application-test.properties", """
                spring.datasource.url=jdbc:postgresql://localhost/test
                management.tracing.enabled=false
                """);

        var findings = DeprecatedConfigurationProperties.scan(DEPRECATED, List.of(properties));

        assertThat(findings).singleElement()
            .satisfies(finding -> {
                assertThat(finding.file()).isEqualTo(properties);
                assertThat(finding.key()).isEqualTo("management.tracing.enabled");
            });
    }

    @Test
    void cleanFilesHaveNoFindings() throws IOException {
        var yaml = write("application.yml", """
                management:
                  tracing:
                    export:
                      enabled: false
                  tracingenabled: true
                """);

        assertThat(DeprecatedConfigurationProperties.scan(DEPRECATED, List.of(yaml))).isEmpty();
        assertThat(DeprecatedConfigurationProperties.keys(yaml)).containsExactly("management.tracing.export.enabled", "management.tracingenabled");
    }

    @Test
    void configurationFilesListsOnlyTheApplicationFilesOfTheDirectory() throws IOException {
        var main = write("application.yaml", "spring: {}\n");
        var profile = write("application-prod.yml", "spring: {}\n");
        var properties = write("application.properties", "a=b\n");
        write("bootstrap.yaml", "spring: {}\n");
        write("application.json", "{}\n");
        Files.createDirectories(directory.resolve("db"));

        assertThat(DeprecatedConfigurationProperties.configurationFiles(directory)).containsExactly(profile, properties, main);
        assertThat(DeprecatedConfigurationProperties.configurationFiles(directory.resolve("missing"))).isEmpty();
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(directory.resolve(name), content);
    }
}
