package fi.poltsi.vempain.common.metadata;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.thebuzzmedia.exiftool.ExifTool;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service turns ExifTool's merged output into results and failures without running the tool: JSON is separated from warnings, a write counts only when
 * ExifTool reported an updated file, unreadable maker notes trigger one retry with {@code -m}, and every call is timed.
 */
class ExifToolServiceUTC {

    @TempDir
    File tempDir;

    private ExifTool exifTool;
    private SimpleMeterRegistry registry;
    private ExifToolService service;

    @BeforeEach
    void setUp() {
        exifTool = mock(ExifTool.class);
        registry = new SimpleMeterRegistry();
        service = new ExifToolService(new ExifToolProperties(), registry, exifTool);
    }

    @Test
    void fullReadUsesTheGroupedArgumentsAndDropsWarningsBeforeTheJson() throws Exception {
        var file = new File(tempDir, "photo.jpg");
        when(exifTool.getRawExifToolOutput(anyList()))
            .thenReturn("Warning: [minor] Bad MakerNotes offset for SonyDateTime\n[{\n  \"SourceFile\": \"photo.jpg\",\n"
                    + "  \"XMP-dc\": {\"Creator\": \"Jane\"}\n}]");

        var metadata = service.readMetadata(file);

        assertThat(metadata.getJSONObject("XMP-dc")
            .getString("Creator")).isEqualTo("Jane");
        ArgumentCaptor<List<String>> arguments = ArgumentCaptor.captor();
        verify(exifTool).getRawExifToolOutput(arguments.capture());
        assertThat(arguments.getValue()).containsExactly("-a", "-u", "-ee", "-api", "RequestAll=3", "-g1", "-J", file.getAbsolutePath());
        assertThat(registry.get(ExifToolService.TOOL_DURATION)
            .tags("tool", "exiftool", "result", "success")
            .timer()
            .count()).isEqualTo(1);
    }

    @Test
    void outputWithoutJsonIsNoMetadata() throws Exception {
        when(exifTool.getRawExifToolOutput(anyList())).thenReturn("Error: File not found - missing.jpg");

        assertThat(service.readMetadataJson(new File(tempDir, "missing.jpg"))).isEmpty();
        assertThat(service.readMetadata(new File(tempDir, "missing.jpg"))).isNull();
        assertThat(service.readTag(new File(tempDir, "missing.jpg"), "ImageWidth")).isEmpty();
        assertThat(ExifToolService.jsonPart(null)).isEmpty();
        assertThat(ExifToolService.jsonPart("[{\"a\":1}")).as("truncated output is not JSON")
            .isEmpty();
    }

    @Test
    void singleTagsAreReadWithTheFlatJsonOutput() throws Exception {
        when(exifTool.getRawExifToolOutput(anyList()))
            .thenReturn("[{\n  \"SourceFile\": \"clip.mp4\",\n  \"VideoFrameRate\": 12,\n  \"Duration\": \"0:00:02\"\n}]");

        assertThat(service.readTag(new File(tempDir, "clip.mp4"), "VideoFrameRate")).isEqualTo("12");
        assertThat(service.readTag(new File(tempDir, "clip.mp4"), "Missing")).isEmpty();
        ArgumentCaptor<List<String>> arguments = ArgumentCaptor.captor();
        verify(exifTool, times(2)).getRawExifToolOutput(arguments.capture());
        assertThat(arguments.getAllValues()
            .getFirst()).startsWith("-j", "-VideoFrameRate");
    }

    @Test
    void writesSucceedOnlyWhenExifToolReportsAnUpdatedFile() throws Exception {
        var file = new File(tempDir, "photo.jpg");
        when(exifTool.getRawExifToolOutput(anyList())).thenReturn("    1 image files updated");
        service.writeValue(file, "XMP-dc", "Creator", "Jane");
        service.wipeAll(file);
        service.copyAllTags(new File(tempDir, "source.jpg"), file);

        when(exifTool.getRawExifToolOutput(anyList())).thenReturn("Warning: Tag 'NoSuchTag' is not defined\n    0 image files updated");
        assertThatThrownBy(() -> service.writeValue(file, "XMP-dc", "NoSuchTag", "x")).isInstanceOf(IOException.class)
            .hasMessageContaining("ExifTool failed for " + file.getAbsolutePath())
            .hasMessageContaining("NoSuchTag");
        assertThat(registry.get(ExifToolService.TOOL_DURATION)
            .tags("tool", "exiftool", "result", "success")
            .timer()
            .count()).isEqualTo(4);
    }

    @Test
    void unreadableMakerNotesAreRetriedWithMinorErrorsIgnored() throws Exception {
        var file = new File(tempDir, "photo.jpg");
        when(exifTool.getRawExifToolOutput(anyList()))
            .thenReturn("Error: [minor] Bad MakerNotes offset for SonyDateTime - photo.jpg\n    0 image files updated", "    1 image files updated");

        service.writeSubjects(file, List.of("alpha", " ", "beta"));

        ArgumentCaptor<List<String>> arguments = ArgumentCaptor.captor();
        verify(exifTool, times(2)).getRawExifToolOutput(arguments.capture());
        List<String> first = arguments.getAllValues()
            .get(0);
        List<String> retry = arguments.getAllValues()
            .get(1);
        assertThat(first).doesNotContain("-m")
            .contains("-XMP-dc:Subject=alpha", "-XMP-dc:Subject=beta", "-XMP-lr:HierarchicalSubject=alpha", "-XMP-lr:WeightedFlatSubject=beta",
                    "-IPTC:Keywords=alpha")
            .doesNotContain("-XMP-dc:Subject= ");
        assertThat(retry.get(1)).isEqualTo("-m");
        assertThat(retry.getLast()).isEqualTo(file.getAbsolutePath());
    }

    @Test
    void anEmptySubjectListRemovesEveryLocation() throws Exception {
        when(exifTool.getRawExifToolOutput(anyList())).thenReturn("    1 image files updated");

        service.writeSubjects(new File(tempDir, "photo.jpg"), List.of());

        ArgumentCaptor<List<String>> arguments = ArgumentCaptor.captor();
        verify(exifTool).getRawExifToolOutput(arguments.capture());
        assertThat(arguments.getValue()).contains("-XMP-dc:Subject=", "-XMP-lr:HierarchicalSubject=", "-XMP-lr:WeightedFlatSubject=", "-IPTC:Keywords=");
    }

    @Test
    void invalidNamesAndReadOnlyGroupsAreRefusedBeforeRunningExifTool() throws Exception {
        var file = new File(tempDir, "photo.jpg");

        assertThatThrownBy(() -> service.writeValue(file, "Composite", "ImageSize", "9x9")).isInstanceOf(IOException.class)
            .hasMessageContaining("read-only");
        assertThatThrownBy(() -> service.writeValue(file, "XMP-dc", "Creator<", "x")).isInstanceOf(IOException.class)
            .hasMessageContaining("Invalid metadata group or key");
        verify(exifTool, never()).getRawExifToolOutput(anyList());
        assertThat(ExifToolService.isWritableGroup("XMP-dc")).isTrue();
        assertThat(ExifToolService.isWritableGroup("File")).isFalse();
        assertThat(ExifToolService.isValidTagName("ID3v2_3")).isTrue();
        assertThat(ExifToolService.isValidTagName("-m")).isFalse();
        assertThat(ExifToolService.isValidTagName(null)).isFalse();
    }

    @Test
    void replacingFromJsonWipesThenImportsAndOnlyLogsFailures() throws Exception {
        var file = new File(tempDir, "photo.jpg");
        when(exifTool.getRawExifToolOutput(anyList())).thenReturn("    1 image files updated");

        service.replaceMetadataFromJson(file, "[{\"XMP-dc:Creator\":\"Json Creator\"}]");

        ArgumentCaptor<List<String>> arguments = ArgumentCaptor.captor();
        verify(exifTool, times(2)).getRawExifToolOutput(arguments.capture());
        assertThat(arguments.getAllValues()
            .get(0)).containsExactly("-overwrite_original_in_place", "-all=", file.getAbsolutePath());
        assertThat(arguments.getAllValues()
            .get(1)
            .get(1)).startsWith("-json=");

        // Empty inputs do nothing and a failing ExifTool is logged, not thrown
        service.replaceMetadataFromJson(null, "[{}]");
        service.replaceMetadataFromJson(file, " ");
        when(exifTool.getRawExifToolOutput(anyList())).thenThrow(new IOException("down"));
        service.replaceMetadataFromJson(file, "[{}]");
        verify(exifTool, times(3)).getRawExifToolOutput(anyList());
        assertThat(registry.get(ExifToolService.TOOL_DURATION)
            .tags("tool", "exiftool", "result", "failure")
            .timer()
            .count()).isEqualTo(1);
    }

    @Test
    void closingForgetsTheFacade() throws Exception {
        service.close();
        verify(exifTool).close();
        service.close();
        verify(exifTool, times(1)).close();
    }

    @Test
    void availabilityFollowsTheConfiguredPath() {
        var properties = new ExifToolProperties();
        properties.setPath(new File(tempDir, "not-there").getAbsolutePath());
        assertThat(new ExifToolService(properties, registry).isAvailable()).isFalse();
        properties.setPath(" ");
        assertThat(new ExifToolService(properties, registry).isAvailable()).isFalse();
        properties.setPath("sh");
        assertThat(new ExifToolService(properties, registry).isAvailable()).isTrue();
        assertThat(new ExifToolService(properties, registry).getPath()).isEqualTo("sh");
    }
}
