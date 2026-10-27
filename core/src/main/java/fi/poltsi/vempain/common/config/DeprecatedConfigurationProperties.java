package fi.poltsi.vempain.common.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Finds configuration keys that Spring Boot's configuration metadata marks deprecated. Boot itself only logs such keys at startup (and only with the properties
 * migrator on the class path), so every backend has a unit test that scans its {@code application*.yaml|yml|properties} files with this class and fails the
 * build on a finding: deprecated 3rd-party functionality is replaced, never carried along, exactly like the {@code -Werror} javac setting does for deprecated
 * APIs.
 * <p>
 * The deprecations come from every {@code META-INF/spring-configuration-metadata.json} on the class path (Boot's own modules and any library that ships
 * metadata), so the check follows the Boot version of the build automatically. A key is reported when it, or one of its dotted prefixes, is a deprecated
 * property (a deprecated map property deprecates every entry under it). List indices are ignored and the Gradle {@code @placeholder@} tokens of the resource
 * filtering are tolerated.
 */
public final class DeprecatedConfigurationProperties {

    private static final String METADATA_RESOURCE = "META-INF/spring-configuration-metadata.json";
    private static final Pattern BUILD_PLACEHOLDER = Pattern.compile("@([A-Za-z0-9_.\\-]+)@");
    private static final Pattern LIST_INDEX = Pattern.compile("\\[[^]]*]");
    private static final Pattern CONFIGURATION_FILE = Pattern.compile("application(-[A-Za-z0-9_-]+)?\\.(ya?ml|properties)");

    private DeprecatedConfigurationProperties() {}

    /** A deprecated property as declared in the metadata; {@code replacement}, {@code level} and {@code reason} are null when not declared. */
    public record Deprecation(String property, String replacement, String level, String reason) {}

    /** A configuration key of a file that is (or lies under) a deprecated property. */
    public record Finding(Path file, String key, Deprecation deprecation) {

        public String describe() {
            var text = new StringBuilder(file.toString()).append(": ")
                .append(key)
                .append(" is deprecated");
            if (deprecation.level() != null) {
                text.append(" (level ")
                    .append(deprecation.level())
                    .append(')');
            }
            if (!key.equals(deprecation.property())) {
                text.append(" through ")
                    .append(deprecation.property());
            }
            if (deprecation.replacement() != null) {
                text.append(", use ")
                    .append(deprecation.replacement());
            }
            if (deprecation.reason() != null
                && !deprecation.reason()
                    .isBlank()) {
                text.append(": ")
                    .append(deprecation.reason());
            }
            return text.toString();
        }
    }

    /** The deprecated properties declared by the configuration metadata on the context class loader's class path, by property name. */
    public static Map<String, Deprecation> deprecatedProperties() {
        return deprecatedProperties(Thread.currentThread()
            .getContextClassLoader());
    }

    public static Map<String, Deprecation> deprecatedProperties(ClassLoader classLoader) {
        var mapper = new ObjectMapper();
        var deprecated = new TreeMap<String, Deprecation>();
        try {
            for (URL metadata : Collections.list(classLoader.getResources(METADATA_RESOURCE))) {
                try (var input = metadata.openStream()) {
                    for (JsonNode property : mapper.readTree(input)
                        .path("properties")) {
                        var name = property.path("name")
                            .asString();
                        var deprecation = property.get("deprecation");
                        if (deprecation != null) {
                            deprecated.put(name,
                                    new Deprecation(name, text(deprecation, "replacement"), text(deprecation, "level"), text(deprecation, "reason")));
                        } else if (property.path("deprecated")
                            .asBoolean(false)) {
                            deprecated.put(name, new Deprecation(name, null, null, null));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Reading " + METADATA_RESOURCE + " from the class path failed", e);
        }
        return deprecated;
    }

    /** The {@code application*.yaml|yml|properties} files directly inside the directory (none when the directory does not exist). */
    public static List<Path> configurationFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                .filter(file -> CONFIGURATION_FILE.matcher(file.getFileName()
                    .toString())
                    .matches())
                .sorted()
                .toList();
        }
    }

    /** Scans the files against the deprecations of the class path metadata. */
    public static List<Finding> scan(Collection<Path> files) {
        return scan(deprecatedProperties(), files);
    }

    public static List<Finding> scan(Map<String, Deprecation> deprecated, Collection<Path> files) {
        var findings = new ArrayList<Finding>();
        for (Path file : files) {
            for (String key : keys(file)) {
                var deprecation = lookup(deprecated, key);
                if (deprecation != null) {
                    findings.add(new Finding(file, key, deprecation));
                }
            }
        }
        return findings;
    }

    /** Every dotted key the file binds, list indices removed, in sorted order. */
    static Set<String> keys(Path file) {
        var name = file.getFileName()
            .toString();
        try {
            var content = BUILD_PLACEHOLDER.matcher(Files.readString(file))
                .replaceAll("placeholder-$1");
            var resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), name);
            Properties properties;
            if (name.endsWith(".properties")) {
                properties = PropertiesLoaderUtils.loadProperties(resource);
            } else {
                var factory = new YamlPropertiesFactoryBean();
                factory.setResources(resource);
                // Not a Spring bean here: the singleton mode would wait for afterPropertiesSet() and return null
                factory.setSingleton(false);
                properties = factory.getObject();
            }
            if (properties == null) {
                return Set.of();
            }
            // keySet(), not stringPropertyNames(): the YAML factory stores booleans and numbers as such, and those entries must count too
            return properties.keySet()
                .stream()
                .map(key -> LIST_INDEX.matcher(String.valueOf(key))
                    .replaceAll(""))
                .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("Reading " + file + " failed", e);
        }
    }

    private static Deprecation lookup(Map<String, Deprecation> deprecated, String key) {
        var candidate = key;
        while (true) {
            var deprecation = deprecated.get(candidate);
            if (deprecation != null) {
                return deprecation;
            }
            var dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            candidate = candidate.substring(0, dot);
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null
               || value.isNull() ? null : value.asString();
    }
}
