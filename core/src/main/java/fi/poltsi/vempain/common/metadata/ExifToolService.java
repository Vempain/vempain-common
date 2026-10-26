package fi.poltsi.vempain.common.metadata;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import jakarta.annotation.PreDestroy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.thebuzzmedia.exiftool.ExecutionStrategy;
import com.thebuzzmedia.exiftool.ExifTool;
import com.thebuzzmedia.exiftool.ExifToolBuilder;
import com.thebuzzmedia.exiftool.core.strategies.DefaultStrategy;
import com.thebuzzmedia.exiftool.core.strategies.PoolStrategy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place the Vempain backends run ExifTool. Commands go to a pool of persistent {@code -stay_open} processes (exiftool-lib with
 * {@link StayOpenExifToolStrategy}), so a call costs the work and not a Perl start-up; the process is created on the first command and restarted when it dies.
 * Nothing is cached between calls: every command reads the file as it is on disk at that moment, which is what the refresh of modified originals relies on.
 * Every call is timed as {@code vempain.tool.duration{tool=exiftool,result}}.
 * <p>
 * Reads return ExifTool's JSON; writes change the file in place ({@code -overwrite_original_in_place}) and fail with an {@link IOException} carrying ExifTool's
 * output when it did not report an updated file. A write refused only because of unreadable maker notes is retried with minor errors ignored ({@code -m}), as
 * the file backend has always done.
 */
@Slf4j
@Service
public class ExifToolService implements AutoCloseable {

    public static final String TOOL_DURATION = "vempain.tool.duration";
    public static final String TOOL = "exiftool";
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";

    /** The full read: every tag including duplicates, unknown and embedded ones, grouped by family 1 group, as JSON. */
    static final List<String> FULL_READ_ARGUMENTS = List.of("-a", "-u", "-ee", "-api", "RequestAll=3", "-g1", "-J");
    static final String OVERWRITE_IN_PLACE = "-overwrite_original_in_place";
    static final String IGNORE_MINOR_ERRORS = "-m";

    /** ExifTool family 1 groups that describe the file or the extraction itself and can never be written back into a file. */
    private static final Set<String> READ_ONLY_GROUPS = Set.of("ExifTool", "File", "System", "Composite");
    /**
     * Group and tag names ExifTool accepts on the command line; anything else is refused so that request text can never become an ExifTool option or a
     * {@code -TAG+=}/{@code -TAG<=} operator.
     */
    private static final Pattern TAG_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    /** ExifTool's summary line of a successful write: "1 image files updated" or "1 output files created". */
    private static final Pattern FILES_WRITTEN = Pattern.compile("^\\s*([1-9]\\d*) (?:image|output) files? (?:updated|created)\\s*$", Pattern.MULTILINE);

    private final ExifToolProperties properties;
    private final MeterRegistry registry;
    private volatile ExifTool exifTool;

    @Autowired
    public ExifToolService(ExifToolProperties properties, ObjectProvider<MeterRegistry> registry) {
        this(properties, registry.getIfAvailable(() -> Metrics.globalRegistry));
    }

    public ExifToolService(ExifToolProperties properties, MeterRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * Uses an already built exiftool-lib facade; for tests.
     */
    ExifToolService(ExifToolProperties properties, MeterRegistry registry, ExifTool exifTool) {
        this(properties, registry);
        this.exifTool = exifTool;
    }

    // Reading ---------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Every tag of the file grouped by family 1 group, as ExifTool prints it with {@code -a -u -ee -api RequestAll=3 -g1 -J}: a JSON array with one object.
     * Empty when ExifTool printed no JSON for the file.
     */
    public String readMetadataJson(File file) throws IOException {
        var arguments = new ArrayList<>(FULL_READ_ARGUMENTS);
        arguments.add(file.getAbsolutePath());
        return jsonPart(run(arguments));
    }

    /**
     * The grouped metadata of the file as the first object of {@link #readMetadataJson}, or {@code null} when ExifTool returned none.
     */
    public JSONObject readMetadata(File file) throws IOException {
        var json = readMetadataJson(file);
        if (json.isBlank()) {
            log.warn("No metadata found for file: {}", file.getAbsolutePath());
            return null;
        }
        try {
            return MetadataJson.metadataToJsonObject(json);
        } catch (JSONException e) {
            throw new IOException("ExifTool returned unparseable JSON for " + file.getAbsolutePath(), e);
        }
    }

    /**
     * Only the named tags of the file as flat ExifTool {@code -j} JSON (an array with one object, the tags on its root level); empty when none exists.
     */
    public String readTagsJson(File file, String... tags) throws IOException {
        var arguments = new ArrayList<String>();
        arguments.add("-j");
        for (var tag : tags) {
            arguments.add("-" + tag);
        }
        arguments.add(file.getAbsolutePath());
        return jsonPart(run(arguments));
    }

    /**
     * The value of one tag of the file as text, like {@code exiftool -j -TAG file}; empty when the file does not carry it.
     */
    public String readTag(File file, String tag) throws IOException {
        var json = readTagsJson(file, tag);
        if (json.isBlank()) {
            return "";
        }
        try {
            var value = MetadataJson.firstValue(MetadataJson.metadataToJsonObject(json), tag);
            return value == null ? "" : value;
        } catch (JSONException e) {
            log.error("Failed to parse the ExifTool output for tag {} of {}", tag, file.getAbsolutePath(), e);
            return "";
        }
    }

    // Writing ---------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Copies every tag of the source into the destination in place ({@code -TagsFromFile source -all:all}).
     */
    public void copyAllTags(File source, File destination) throws IOException {
        runWrite(List.of(OVERWRITE_IN_PLACE, "-TagsFromFile", source.getAbsolutePath(), "-all:all", destination.getAbsolutePath()), destination);
    }

    /**
     * Writes one {@code -GROUP:KEY=value} into the file in place; an empty or null value removes the entry. The group and key must pass
     * {@link #isValidTagName(String)} and the group must be {@link #isWritableGroup(String) writable}. ExifTool refuses to write unknown or read-only tags,
     * which surfaces as an {@link IOException}.
     */
    public void writeValue(File file, String group, String key, String value) throws IOException {
        if (!isValidTagName(group)
            || !isValidTagName(key)) {
            throw new IOException("Invalid metadata group or key: " + group + ":" + key);
        }
        if (!isWritableGroup(group)) {
            throw new IOException("Metadata group is read-only: " + group);
        }
        writeWithMakerNotesRetry(List.of("-" + group + ":" + key + "=" + (value == null ? "" : value)), file);
    }

    /**
     * Writes the subjects as {@code XMP-dc:Subject}, {@code XMP-lr:HierarchicalSubject}, {@code XMP-lr:WeightedFlatSubject} and {@code IPTC:Keywords},
     * replacing the previous values; an empty list removes them.
     */
    public void writeSubjects(File file, List<String> subjects) throws IOException {
        var assignments = new ArrayList<String>();
        for (var tag : List.of("XMP-dc:Subject", "XMP-lr:HierarchicalSubject", "XMP-lr:WeightedFlatSubject", "IPTC:Keywords")) {
            var written = false;
            for (var subject : subjects) {
                if (subject != null
                    && !subject.isBlank()) {
                    assignments.add("-" + tag + "=" + subject);
                    written = true;
                }
            }
            if (!written) {
                // Without any value ExifTool would leave the tag untouched; an empty assignment removes the remaining subjects
                assignments.add("-" + tag + "=");
            }
        }
        writeWithMakerNotesRetry(assignments, file);
    }

    /**
     * Removes every writable tag of the file in place ({@code -all=}).
     */
    public void wipeAll(File file) throws IOException {
        runWrite(List.of(OVERWRITE_IN_PLACE, "-all=", file.getAbsolutePath()), file);
    }

    /**
     * Imports an ExifTool JSON array (as {@code -json=FILE} reads it) into the file in place. The array holds one object whose keys are tag names; a
     * {@code SourceFile} entry is not needed for a single object.
     */
    public void importJson(File file, String metadataJson) throws IOException {
        var tempJson = Files.createTempFile("vempain-meta-", ".json");
        try {
            Files.writeString(tempJson, metadataJson, StandardCharsets.UTF_8);
            runWrite(List.of(OVERWRITE_IN_PLACE, "-json=" + tempJson.toAbsolutePath(), file.getAbsolutePath()), file);
        } finally {
            Files.deleteIfExists(tempJson);
        }
    }

    /**
     * Replaces the metadata of the file with the given JSON: the file is wiped first and the JSON imported afterwards. Problems are logged, not thrown, because
     * the derivative the file backend builds is still usable without metadata.
     */
    public void replaceMetadataFromJson(File file, String metadataJson) {
        if (file == null
            || metadataJson == null
            || metadataJson.isBlank()) {
            log.warn("replaceMetadataFromJson called with empty inputs. File: {}, metadataJson empty: {}", file, metadataJson == null
                                                                                                                 || metadataJson.isBlank());
            return;
        }
        try {
            wipeAll(file);
            importJson(file, metadataJson);
            log.debug("Metadata successfully written to {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write metadata JSON to file {}", file.getAbsolutePath(), e);
        }
    }

    /**
     * @return whether values of the ExifTool family 1 group can be written back into the files
     */
    public static boolean isWritableGroup(String group) {
        return group != null
               && !READ_ONLY_GROUPS.contains(group);
    }

    /**
     * @return whether the text is a syntactically safe ExifTool group or tag name
     */
    public static boolean isValidTagName(String name) {
        return name != null
               && TAG_NAME_PATTERN.matcher(name)
                   .matches();
    }

    @Override
    @PreDestroy
    public void close() {
        var tool = exifTool;
        exifTool = null;
        if (tool != null) {
            try {
                tool.close();
            } catch (Exception e) {
                log.warn("Closing the exiftool processes failed: {}", e.getMessage());
            }
        }
    }

    // Running ---------------------------------------------------------------------------------------------------------------------------------------

    private void writeWithMakerNotesRetry(List<String> assignments, File file) throws IOException {
        var arguments = new ArrayList<String>();
        arguments.add(OVERWRITE_IN_PLACE);
        arguments.addAll(assignments);
        arguments.add(file.getAbsolutePath());
        try {
            runWrite(arguments, file);
        } catch (IOException e) {
            if (!isUnparseableMakerNotesError(e)) {
                throw e;
            }
            log.warn("Retrying metadata write with ExifTool minor-error suppression for {}", file.getAbsolutePath());
            var retry = new ArrayList<>(arguments);
            retry.add(1, IGNORE_MINOR_ERRORS);
            runWrite(retry, file);
        }
    }

    static boolean isUnparseableMakerNotesError(IOException exception) {
        var message = exception.getMessage();
        return message != null
               && (message.contains("Bad MakerNotes offset")
                   || message.contains("Maker notes could not be parsed"));
    }

    /**
     * Runs a writing command; ExifTool reports a successful write with a "N image files updated" line, anything else is a failure.
     */
    private void runWrite(List<String> arguments, File file) throws IOException {
        var output = run(arguments);
        if (!FILES_WRITTEN.matcher(output)
            .find()) {
            throw new IOException("ExifTool failed for " + file.getAbsolutePath() + ": " + output.trim());
        }
    }

    /**
     * Runs one ExifTool command and returns its complete output (standard output and error merged), recording the duration.
     */
    String run(List<String> arguments) throws IOException {
        log.debug("Running exiftool with arguments: {}", arguments);
        var started = System.nanoTime();
        var result = RESULT_FAILURE;
        try {
            var output = tool().getRawExifToolOutput(arguments);
            result = RESULT_SUCCESS;
            return output == null ? "" : output;
        } finally {
            registry.timer(TOOL_DURATION, "tool", TOOL, "result", result)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * The JSON array of an ExifTool output: warnings printed before it are dropped. Empty when the output holds no JSON.
     */
    static String jsonPart(String output) {
        if (output == null) {
            return "";
        }
        var lines = output.split("\\R");
        var start = IntStream.range(0, lines.length)
            .filter(index -> lines[index].startsWith("["))
            .findFirst();
        if (start.isEmpty()) {
            return "";
        }
        var json = String.join("\n", List.of(lines)
            .subList(start.getAsInt(), lines.length))
            .trim();
        return json.endsWith("]") ? json : "";
    }

    private ExifTool tool() {
        var tool = exifTool;
        if (tool == null) {
            synchronized (this) {
                tool = exifTool;
                if (tool == null) {
                    tool = build(properties);
                    exifTool = tool;
                }
            }
        }
        return tool;
    }

    static ExifTool build(ExifToolProperties properties) {
        ExecutionStrategy strategy;
        if (properties.isStayOpen()) {
            var size = Math.max(1, properties.getPoolSize());
            var processes = new ArrayList<ExecutionStrategy>(size);
            for (int i = 0; i < size; i++) {
                processes.add(new StayOpenExifToolStrategy(properties.getIdleTimeoutMs()));
            }
            strategy = new PoolStrategy(processes);
        } else {
            strategy = new DefaultStrategy();
        }
        log.info("ExifTool runs from '{}' with {}", properties.getPath(),
                properties.isStayOpen() ? Math.max(1, properties.getPoolSize()) + " persistent process(es)" : "one process per command");
        return new ExifToolBuilder().withPath(properties.getPath())
            .withExecutor(new MergedOutputCommandExecutor())
            .withStrategy(strategy)
            .build();
    }

    /**
     * Parses a flat or grouped ExifTool JSON array into its first object; for callers that already hold the text.
     */
    public static JSONObject firstObject(String json) {
        if (json == null
            || json.isBlank()) {
            return null;
        }
        var array = new JSONArray(json);
        return array.isEmpty() ? null : array.getJSONObject(0);
    }

    /**
     * @return the configured executable, for diagnostics and start-up checks
     */
    public String getPath() {
        return properties.getPath();
    }

    /**
     * @return whether {@code vempain.exiftool.path} points at an executable file, or names a command on the PATH
     */
    public boolean isAvailable() {
        var path = properties.getPath();
        if (path == null
            || path.isBlank()) {
            return false;
        }
        var file = new File(path);
        if (file.isAbsolute()
            || path.contains(File.separator)) {
            return file.canExecute();
        }
        var systemPath = System.getenv("PATH");
        if (systemPath == null) {
            return false;
        }
        for (var directory : systemPath.split(File.pathSeparator)) {
            if (new File(directory, path).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
