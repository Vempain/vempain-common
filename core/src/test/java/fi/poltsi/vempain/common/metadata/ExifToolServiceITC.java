package fi.poltsi.vempain.common.metadata;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real exiftool through the persistent process pool: reads, writes, copies, wipes and JSON imports on images written by ImageIO, plus the behaviour
 * the pool must keep (JSON arrays from the first command, concurrent callers, restart after an idle close).
 */
class ExifToolServiceITC {

    @TempDir
    Path tempDirectory;

    private ExifToolService service;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        var properties = new ExifToolProperties();
        assumeTrue(new ExifToolService(properties, new SimpleMeterRegistry()).isAvailable(), "exiftool is required for this test");
        properties.setPoolSize(2);
        properties.setIdleTimeoutMs(300);
        registry = new SimpleMeterRegistry();
        service = new ExifToolService(properties, registry);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private File jpeg(String name) throws IOException {
        var file = tempDirectory.resolve(name)
            .toFile();
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        image.getGraphics()
            .fillRect(0, 0, 16, 16);
        assertThat(ImageIO.write(image, "jpg", file)).isTrue();
        return file;
    }

    @Test
    void writesOverwritesAndRemovesOneGroupKeyValue() throws Exception {
        var image = jpeg("value.jpg");

        service.writeValue(image, "XMP-dc", "Creator", "Jane Doe");
        assertThat(service.readMetadata(image)
            .getJSONObject("XMP-dc")
            .getString("Creator")).isEqualTo("Jane Doe");

        service.writeValue(image, "XMP-dc", "Creator", "Jane A. Doe");
        assertThat(MetadataJson.extractCreatorName(service.readMetadata(image))).isEqualTo("Jane A. Doe");

        service.writeValue(image, "XMP-dc", "Creator", "");
        var afterRemoval = service.readMetadata(image)
            .optJSONObject("XMP-dc");
        assertThat(afterRemoval == null
                   || !afterRemoval.has("Creator"))
            .isTrue();

        // ExifTool itself refuses tags it does not know
        assertThatThrownBy(() -> service.writeValue(image, "XMP-dc", "NoSuchTagInExifTool", "x")).isInstanceOf(IOException.class)
            .hasMessageContaining("NoSuchTagInExifTool");
    }

    @Test
    void listTagsAreJsonArraysFromTheVeryFirstCommand() throws Exception {
        var image = jpeg("subjects.jpg");
        service.writeSubjects(image, List.of("2. maailmansota", "Kanada"));
        service.close();
        // a fresh pool: the first command of a new process must already return arrays, not joined strings
        service = new ExifToolService(service.getPath() == null ? new ExifToolProperties() : propertiesOf(service), registry);

        var metadata = service.readMetadata(image);

        assertThat(metadata.getJSONObject("XMP-dc")
            .getJSONArray("Subject")
            .toList()).containsExactly("2. maailmansota", "Kanada");
        assertThat(metadata.getJSONObject("IPTC")
            .getJSONArray("Keywords")
            .toList()).containsExactly("2. maailmansota", "Kanada");
        assertThat(MetadataJson.extractSubjects(metadata)).containsExactly("2. maailmansota", "Kanada");

        service.writeSubjects(image, List.of());
        assertThat(MetadataJson.extractSubjects(service.readMetadata(image))).isEmpty();
    }

    private static ExifToolProperties propertiesOf(ExifToolService service) {
        var properties = new ExifToolProperties();
        properties.setPath(service.getPath());
        properties.setPoolSize(1);
        return properties;
    }

    @Test
    void copiesImportsAndWipesMetadata() throws Exception {
        var source = jpeg("source.jpg");
        service.writeValue(source, "XMP-dc", "Creator", "Copied Creator");
        var destination = jpeg("destination.jpg");

        service.copyAllTags(source, destination);
        assertThat(MetadataJson.extractCreatorName(service.readMetadata(destination))).isEqualTo("Copied Creator");
        assertThatThrownBy(() -> service.copyAllTags(source, tempDirectory.resolve("missing.jpg")
            .toFile())).isInstanceOf(IOException.class);

        service.replaceMetadataFromJson(destination, "[{\"XMP-dc:Creator\":\"Json Creator\",\"XMP-dc:Description\":\"From JSON\"}]");
        var metadata = service.readMetadata(destination);
        assertThat(MetadataJson.extractCreatorName(metadata)).isEqualTo("Json Creator");
        assertThat(MetadataJson.extractDescription(metadata)).isEqualTo("From JSON");

        service.wipeAll(destination);
        assertThat(MetadataJson.extractCreatorName(service.readMetadata(destination))).isNull();

        assertThat(service.readMetadata(tempDirectory.resolve("missing.jpg")
            .toFile())).isNull();
        assertThat(service.readTag(destination, "ImageWidth")).isEqualTo("16");
        assertThat(service.readTag(destination, "NoSuchTag")).isEmpty();
    }

    @Test
    void concurrentCallersShareThePoolAndAnIdleProcessIsRestarted() throws Exception {
        var images = new File[6];
        for (int i = 0; i < images.length; i++) {
            images[i] = jpeg("concurrent-" + i + ".jpg");
            service.writeValue(images[i], "XMP-dc", "Creator", "Creator " + i);
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<Future<String>>();
            for (var image : images) {
                futures.add(executor.submit(() -> MetadataJson.extractCreatorName(service.readMetadata(image))));
            }
            for (int i = 0; i < images.length; i++) {
                assertThat(futures.get(i)
                    .get()).isEqualTo("Creator " + i);
            }
        }

        Thread.sleep(600);
        assertThat(MetadataJson.extractCreatorName(service.readMetadata(images[0]))).isEqualTo("Creator 0");
        assertThat(registry.get(ExifToolService.TOOL_DURATION)
            .tags("tool", "exiftool", "result", "success")
            .timer()
            .count()).isGreaterThanOrEqualTo(13);
    }
}
