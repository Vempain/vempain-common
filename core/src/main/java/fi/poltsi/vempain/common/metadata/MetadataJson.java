package fi.poltsi.vempain.common.metadata;

import java.awt.Dimension;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import static java.util.Map.entry;

/**
 * Parsers of the ExifTool JSON that {@link ExifToolService} reads: the family 1 grouped output of {@code -g1 -J} (one object whose keys are groups such as
 * {@code EXIF}, {@code XMP-dc} or {@code Composite}) and the flat {@code -j} output. Every method is a pure function of the JSON, so the backends call them
 * statically; the only state they ever see is the snapshot of the file that was read, which keeps a modified original from being served from a cache.
 * {@link #collectStandardMetadataAsJson} builds the metadata written into export derivatives.
 */
@Slf4j
@SuppressWarnings({ "checkstyle:CyclomaticComplexity", "checkstyle:MethodLength" })
public final class MetadataJson {

    private MetadataJson() {}

    private static final String COMPOSITE_KEY = "Composite";
    private static final String EXIF_IFD_KEY = "ExifIFD";
    private static final String EXIF_KEY = "EXIF";
    private static final String FILE_KEY = "File";
    private static final String GPS_KEY = "GPS";
    private static final String IFD0_KEY = "IFD0";
    private static final String IPTC_KEY = "IPTC";
    private static final String SUBIFD_1_KEY = "SUBIFD1";
    private static final String SUBIFD_2_KEY = "SUBIFD2";
    private static final String SUBIFD_3_KEY = "SUBIFD3";
    private static final String SUBIFD_KEY = "SUBIFD";
    private static final String SYSTEM_KEY = "System";
    private static final String XMP_DC_KEY = "XMP-dc";
    private static final String XMP_EXIF_KEY = "XMP-exif";
    private static final String XMP_IPTC_CORE_KEY = "XMP-iptcCore";
    private static final String XMP_KEY = "XMP";
    private static final String XMP_LR_KEY = "XMP-lr";
    private static final String XMP_PHOTOSHOP_KEY = "XMP-photoshop";
    private static final String XMP_XMPMM_KEY = "XMP-xmpMM";
    private static final String XMP_XMP_KEY = "XMP-xmp";
    private static final String XMP_XMP_RIGHTS_KEY = "XMP-xmpRights";

    private static final String BITS_PER_SAMPLE_FIELD = "BitsPerSample";
    private static final String COMPOSITE_IMAGE_SIZE_FIELD = "ImageSize";
    private static final String CREATE_DATE_FIELD = "CreateDate";
    private static final String DATETIME_ORIGINAL_FIELD = "DateTimeOriginal";
    private static final String IFD0_IMAGE_DESCRIPTION_FIELD = "ImageDescription";
    private static final String IPTC_CAPTION_FIELD = "Caption-Abstract";
    private static final String IPTC_KEYWORD_FIELD = "Keywords";
    private static final String MIMETYPE_FIELD = "MIMEType";
    private static final String SUBIFD_IMAGE_HEIGHT_FIELD = "ImageHeight";
    private static final String SUBIFD_IMAGE_WIDTH_FIELD = "ImageWidth";
    private static final String XMP_DESCRIPTION_FIELD = "Description";
    private static final String X_RESOLUTION_FIELD = "XResolution";

    // GPS coordinate precision
    private static final int GPS_DECIMAL_PRECISION = 5;
    private static final long MAX_DURATION_SECONDS = 99_999L;

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    public static Dimension extractImageResolution(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        // First we try to extract the resolution from the Composite section, here it should be like "widthxheight"
        locations.put(COMPOSITE_KEY, List.of(COMPOSITE_IMAGE_SIZE_FIELD));
        var resolution = extractJsonString(jsonObject, locations);

        if (resolution != null
            && !resolution.isBlank()
            && resolution.contains("x")) {
            var parts = resolution.split("x");

            if (parts.length == 2) {
                try {
                    int width = Integer.parseInt(parts[0].trim());
                    int height = Integer.parseInt(parts[1].trim());
                    return new Dimension(width, height);
                } catch (NumberFormatException e) {
                    log.error("Failed to parse image resolution: {}", resolution, e);
                }
            }
        }

        // Next we try to extract the resolution from the SubIFD section, here width and height are separate fields
        locations.put(SUBIFD_KEY, List.of(SUBIFD_IMAGE_HEIGHT_FIELD));
        var ifdHeight = extractJsonNumber(jsonObject, locations);

        if (ifdHeight != null) {
            locations.put(SUBIFD_KEY, List.of(SUBIFD_IMAGE_WIDTH_FIELD));
            var ifdWidth = extractJsonNumber(jsonObject, locations);

            if (ifdWidth != null) {
                return new Dimension(ifdWidth.intValue(), ifdHeight.intValue());
            }
        }

        return null;
    }

    /**
     * Extract the image color depth from the given metadata in JSON format
     *
     * @param jsonObject Extracted JSON formatted metadata (from @MetadataTools.getMetadataAsJSON())
     * @return int value retrieved, or 8 if none were found or parsing failed. 8 is a good default for most images.
     */
    public static int extractImageColorDepth(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(SUBIFD_KEY, List.of(BITS_PER_SAMPLE_FIELD));
        locations.put(IFD0_KEY, List.of(BITS_PER_SAMPLE_FIELD));
        String colorDepthString = null;
        // Let's try to extract it as a string, as sometimes it might be stored as a triplet
        try {
            colorDepthString = extractJsonString(jsonObject, locations);
        } catch (JSONException e) {
            log.warn("Failed to parse color depth as string. Attempting to get number instead");

            try {
                var colorDepthNumber = extractJsonNumber(jsonObject, locations);
                if (colorDepthNumber != null) {
                    return colorDepthNumber.intValue();
                }
            } catch (JSONException e1) {
                log.warn("Failed to parse color depth as number so returning default. Exception: {}", e1.getMessage());
                return 8;
            }
        }

        if (colorDepthString != null) {
            // Match any triplet format like "8 8 8" or "8, 8, 8" or "16 16 16"
            colorDepthString = colorDepthString.replace(",", " ")
                .trim();
            // Then calculate the total color depth by summing the individual channel depths
            if (colorDepthString.matches("(\\d+\\s+){2}\\d+")) {
                var channelBits = colorDepthString.split("\\s+");
                int total = 0;

                for (String channelBit : channelBits) {
                    try {
                        total += Integer.parseInt(channelBit);
                    } catch (NumberFormatException e) {
                        log.error("Failed to parse channel bit part: {}", channelBit, e);
                    }
                }
                if (total > 0) {
                    return total;
                }
            }

            try {
                return Integer.parseInt(colorDepthString);
            } catch (Exception e) {
                log.error("Failed to parse color depth from string: {}", colorDepthString, e);
            }
        }

        // If that fails, let's try to extract it as a number
        var colorDepthNumber = extractJsonNumber(jsonObject, locations);

        if (colorDepthNumber != null) {
            return colorDepthNumber.intValue();
        }

        // Default to 8 if nothing found
        return 8;
    }

    public static int extractImageDpi(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(SUBIFD_KEY, List.of(X_RESOLUTION_FIELD));
        var tagValue = extractJsonNumber(jsonObject, locations);

        if (tagValue == null) {
            log.warn("No XResolution found in metadata");
            return 72; // Default DPI if not found
        }

        return tagValue.intValue();
    }

    /**
     * Extract the artist/performer from audio file metadata (ID3, Vorbis, etc.)
     *
     * @param jsonObject Extracted JSON formatted metadata
     * @return Artist string or null if not found
     */
    public static String extractMusicArtist(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        // Legacy "ID3" group key (kept for backward-compat with older exiftool versions)
        locations.put("ID3", List.of("Artist", "TPE1", "TPE2", "AlbumArtist", "Performer"));
        // exiftool -g1 outputs versioned group names for ID3 tags in MP3 files
        locations.put("ID3v2_4", List.of("Artist", "TPE1", "AlbumArtist", "Performer"));
        locations.put("ID3v2_3", List.of("Artist", "TPE1", "AlbumArtist", "Performer"));
        locations.put("ID3v2_2", List.of("Artist", "TP1", "AlbumArtist", "Performer"));
        locations.put("ID3v1", List.of("Artist"));
        locations.put("Vorbis", List.of("Artist", "Performer", "AlbumArtist"));
        locations.put("iTunes", List.of("Artist"));
        locations.put("QuickTime", List.of("Artist", "TPE1"));
        locations.put("RIFF", List.of("Artist", "IART"));
        locations.put("XMP-dm", List.of("Artist", "AlbumArtist"));
        return extractJsonString(jsonObject, locations);
    }

    /**
     * Extract the album name from audio file metadata.
     *
     * @param jsonObject Extracted JSON formatted metadata
     * @return Album name string or null if not found
     */
    public static String extractMusicAlbum(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put("ID3", List.of("Album", "TALB"));
        // exiftool -g1 versioned ID3 group names for MP3 files
        locations.put("ID3v2_4", List.of("Album", "TALB"));
        locations.put("ID3v2_3", List.of("Album", "TALB"));
        locations.put("ID3v2_2", List.of("Album", "TAL"));
        locations.put("ID3v1", List.of("Album"));
        locations.put("Vorbis", List.of("Album"));
        locations.put("iTunes", List.of("Album"));
        locations.put("QuickTime", List.of("Album", "TALB"));
        locations.put("RIFF", List.of("Product", "IPRD"));
        locations.put("XMP-dm", List.of("Album"));
        return extractJsonString(jsonObject, locations);
    }

    /**
     * Extract the album artist from audio file metadata. Falls back to the track artist if no dedicated album artist field exists.
     */
    public static String extractMusicAlbumArtist(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put("ID3", List.of("AlbumArtist", "Albumartist", "Band", "TPE2", "Artist"));
        // exiftool -g1 versioned ID3 group names for MP3 files
        locations.put("ID3v2_4", List.of("AlbumArtist", "Albumartist", "Band", "TPE2", "Artist"));
        locations.put("ID3v2_3", List.of("AlbumArtist", "Albumartist", "Band", "TPE2", "Artist"));
        locations.put("ID3v2_2", List.of("AlbumArtist", "Albumartist", "Band", "TP2", "Artist"));
        locations.put("ID3v1", List.of("Artist"));
        locations.put("Vorbis", List.of("AlbumArtist", "Albumartist", "Artist"));
        locations.put("iTunes", List.of("AlbumArtist", "Artist"));
        locations.put("QuickTime", List.of("AlbumArtist", "Artist", "TPE2"));
        locations.put("RIFF", List.of("Band", "Artist", "IART"));
        locations.put("XMP-dm", List.of("AlbumArtist", "Artist"));
        return extractJsonString(jsonObject, locations);
    }

    /**
     * Extract the release year from audio file metadata. Only the year part is returned, even if a full date is stored.
     */
    public static Integer extractMusicYear(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put("ID3", List.of("Year", "Date", "TDRC"));
        // exiftool -g1 versioned ID3 group names for MP3 files
        // ID3v2.4 uses RecordingTime (TDRC); ID3v2.3 uses Year (TYER)
        locations.put("ID3v2_4", List.of("RecordingTime", "Year", "Date", "TDRC"));
        locations.put("ID3v2_3", List.of("Year", "Date", "RecordingTime", "TYER"));
        locations.put("ID3v2_2", List.of("Year", "Date", "TYE"));
        locations.put("ID3v1", List.of("Year"));
        locations.put("Vorbis", List.of("Date", "Year"));
        locations.put("iTunes", List.of("Year", "Date"));
        locations.put("QuickTime", List.of("Year", "Date"));
        locations.put("Composite", List.of("DateTimeOriginal"));
        locations.put("XMP-dm", List.of("ReleaseDate"));

        var yearValue = extractJsonScalar(jsonObject, locations);
        if (yearValue == null) {
            return null;
        }

        if (yearValue instanceof Number number) {
            return number.intValue();
        }

        var yearString = Objects.toString(yearValue, null);
        if (yearString == null
            || yearString.isBlank()) {
            return 0;
        }

        var matcher = java.util.regex.Pattern.compile("(\\d{4})")
            .matcher(yearString);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        var fallback = parseIntegerWithUnits(yearString);
        if (fallback != null) {
            return fallback;
        }

        log.debug("Could not parse music year from: {}", yearString);
        return 0;
    }

    /**
     * Extract the track title from audio file metadata.
     *
     * @param jsonObject Extracted JSON formatted metadata
     * @return Track title string or null if not found
     */
    public static String extractMusicTitle(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put("ID3", List.of("Title", "TIT2", "TIT1"));
        // exiftool -g1 versioned ID3 group names for MP3 files
        locations.put("ID3v2_4", List.of("Title", "TIT2", "TIT1"));
        locations.put("ID3v2_3", List.of("Title", "TIT2", "TIT1"));
        locations.put("ID3v2_2", List.of("Title", "TT2", "TT1"));
        locations.put("ID3v1", List.of("Title"));
        locations.put("Vorbis", List.of("Title"));
        locations.put("iTunes", List.of("Title"));
        locations.put("QuickTime", List.of("Title", "TIT2"));
        locations.put("RIFF", List.of("Name", "INAM"));
        locations.put("XMP-dm", List.of("TrackName"));
        return extractJsonString(jsonObject, locations);
    }

    /**
     * Extract the track number from audio file metadata.
     *
     * @param jsonObject Extracted JSON formatted metadata
     * @return Track number or null if not found or not parseable
     */
    public static Integer extractMusicTrackNumber(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put("ID3", List.of("TrackNumber", "TRCK"));
        // exiftool -g1 versioned ID3 group names; key is "Track" (may contain "N/total" format)
        locations.put("ID3v2_4", List.of("Track", "TrackNumber", "TRCK"));
        locations.put("ID3v2_3", List.of("Track", "TrackNumber", "TRCK"));
        locations.put("ID3v2_2", List.of("Track", "TrackNumber", "TRK"));
        locations.put("ID3v1", List.of("Track", "TrackNumber"));
        locations.put("Vorbis", List.of("TrackNumber", "Tracknumber", "Track"));
        locations.put("iTunes", List.of("TrackNumber"));
        locations.put("QuickTime", List.of("TrackNumber", "TRCK"));
        locations.put("XMP-dm", List.of("TrackNumber"));

        var trackValue = extractJsonScalar(jsonObject, locations);
        if (trackValue == null) {
            return null;
        }

        if (trackValue instanceof Number number) {
            return number.intValue();
        }

        var trackStr = Objects.toString(trackValue, null);
        if (trackStr == null
            || trackStr.isBlank()) {
            return 0;
        }
        // Track may be in format "1/12" (track/total); take only the track part
        var parts = trackStr.split("/");
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            var fallback = parseIntegerWithUnits(trackStr);
            if (fallback != null) {
                return fallback;
            }
            log.debug("Could not parse track number from: {}", trackStr);
            return 0;
        }
    }

    /**
     * Extract the total track count from audio file metadata.
     */
    public static Integer extractMusicTrackTotal(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put("ID3", List.of("TrackTotal", "Track", "TRCK"));
        // exiftool -g1 versioned ID3 group names; "Track" contains "N/total" format in ID3
        locations.put("ID3v2_4", List.of("Track", "TrackTotal", "TRCK"));
        locations.put("ID3v2_3", List.of("Track", "TrackTotal", "TRCK"));
        locations.put("ID3v2_2", List.of("Track", "TrackTotal", "TRK"));
        locations.put("ID3v1", List.of("Track", "TrackTotal"));
        locations.put("Vorbis", List.of("TrackTotal", "Tracktotal", "TrackNumber", "Track"));
        locations.put("iTunes", List.of("TrackTotal", "TrackCount", "TrackNumber"));
        locations.put("QuickTime", List.of("TrackTotal", "TrackNumber", "TRCK"));
        locations.put("XMP-dm", List.of("TrackTotal", "TrackNumber"));

        var trackValue = extractJsonScalar(jsonObject, locations);
        if (trackValue == null) {
            return null;
        }

        if (trackValue instanceof Number number) {
            return number.intValue();
        }

        var trackStr = Objects.toString(trackValue, null);
        if (trackStr == null
            || trackStr.isBlank()) {
            return 0;
        }

        if (trackStr.contains("/")) {
            var parts = trackStr.split("/");
            if (parts.length > 1) {
                var totalPart = parts[1].trim();
                try {
                    return Integer.parseInt(totalPart);
                } catch (NumberFormatException ignored) {
                    var fallbackTotal = parseIntegerWithUnits(totalPart);
                    if (fallbackTotal != null) {
                        return fallbackTotal;
                    }
                }
            }
        }

        var fallback = parseIntegerWithUnits(trackStr);
        if (fallback != null) {
            return fallback;
        }

        log.debug("Could not parse track total from: {}", trackStr);
        return 0;
    }

    /**
     * Extract the genre from audio file metadata.
     *
     * @param jsonObject Extracted JSON formatted metadata
     * @return Genre string or null if not found
     */
    public static String extractMusicGenre(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put("ID3", List.of("Genre", "TCON"));
        // exiftool -g1 versioned ID3 group names for MP3 files
        locations.put("ID3v2_4", List.of("Genre", "TCON"));
        locations.put("ID3v2_3", List.of("Genre", "TCON"));
        locations.put("ID3v2_2", List.of("Genre", "TCO"));
        locations.put("ID3v1", List.of("Genre"));
        locations.put("Vorbis", List.of("Genre"));
        locations.put("iTunes", List.of("Genre"));
        locations.put("QuickTime", List.of("Genre", "TCON"));
        locations.put("RIFF", List.of("Genre", "IGNR"));
        locations.put("XMP-dm", List.of("Genre"));
        return extractJsonString(jsonObject, locations);
    }

    /**
     * Extract the music description/comment from audio file metadata.
     * <ul>
     * <li>FLAC files store it in the {@code Vorbis} group under the {@code Description} field.</li>
     * <li>MP3 files store it in versioned ID3 groups ({@code ID3v2_4}, {@code ID3v2_3}, {@code ID3v2_2}, {@code ID3v1}) under the {@code Comment} field.</li>
     * </ul>
     *
     * @param jsonObject Extracted JSON formatted metadata (from exiftool {@code -g1 -J})
     * @return Description/comment string, or {@code null} if not present
     */
    public static String extractMusicDescription(JSONObject jsonObject) {
        if (jsonObject == null) {
            return null;
        }
        var locations = new HashMap<String, List<String>>();
        // FLAC — Vorbis comment block
        locations.put("Vorbis", List.of("Description", "Comment"));
        // MP3 — versioned ID3 group names produced by exiftool -g1
        locations.put("ID3v2_4", List.of("Comment"));
        locations.put("ID3v2_3", List.of("Comment"));
        locations.put("ID3v2_2", List.of("Comment"));
        locations.put("ID3v1", List.of("Comment"));
        // Legacy generic ID3 key (older exiftool versions)
        locations.put("ID3", List.of("Comment", "Description"));
        // iTunes / QuickTime
        locations.put("iTunes", List.of("Comment", "Description"));
        locations.put("QuickTime", List.of("Comment", "Description"));
        return extractJsonString(jsonObject, locations);
    }

    /**
     * Checks whether the given metadata JSON object contains music-specific metadata (at minimum an artist, title, album or genre field).
     * <p>
     * When exiftool is run with {@code -g1}, ID3 tags in MP3 files appear under versioned group keys ({@code ID3v2_4}, {@code ID3v2_3}, {@code ID3v2_2},
     * {@code ID3v1}) rather than the generic {@code "ID3"} key. The extraction helpers handle all known variants, so this method simply delegates to them.
     * </p>
     *
     * @param jsonObject Extracted JSON formatted metadata
     * @return true if music metadata is present, false otherwise
     */
    public static boolean hasMusicMetadata(JSONObject jsonObject) {
        if (jsonObject == null) {
            return false;
        }
        return extractMusicArtist(jsonObject) != null
               || extractMusicTitle(jsonObject) != null
               || extractMusicAlbum(jsonObject) != null
               || extractMusicGenre(jsonObject) != null;
    }

    /**
     * The free-text description of a file from the ExifTool {@code -g1} metadata, in order of preference: {@code XMP-dc:Description},
     * {@code IFD0:ImageDescription} and finally the legacy {@code IPTC:Caption-Abstract}. Blank values are skipped.
     *
     * @return the first non-blank description, or {@code null} when the file carries none
     */
    public static String extractDescription(JSONObject jsonObject) {
        if (jsonObject == null) {
            return null;
        }
        var locations = new LinkedHashMap<String, List<String>>();
        locations.put(XMP_DC_KEY, List.of(XMP_DESCRIPTION_FIELD));
        locations.put(IFD0_KEY, List.of(IFD0_IMAGE_DESCRIPTION_FIELD));
        locations.put(IPTC_KEY, List.of(IPTC_CAPTION_FIELD));

        for (var location : locations.entrySet()) {
            var group = jsonObject.optJSONObject(location.getKey());
            if (group == null) {
                continue;
            }
            for (var key : location.getValue()) {
                var value = Objects.toString(group.opt(key), null);
                if (value != null
                    && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    /**
     * Extract the mimetype from the given metadata
     *
     * @param jsonObject Extracted JSON formatted metadata (from @MetadataTools.getMetadataAsJSON())
     * @return String value retrieved, or null if none were found
     */
    public static String extractMimetype(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(FILE_KEY, List.of(MIMETYPE_FIELD));
        locations.put(XMP_KEY, List.of(MIMETYPE_FIELD));

        return extractJsonString(jsonObject, locations);
    }

    /**
     * Extract the original time of creation from the given metadata in JSON format
     *
     * @param jsonObject Extracted JSON formatted metadata (from @MetadataTools.getMetadataAsJSON())
     * @return String value retrieved, or null if none were found
     */
    public static String extractOriginalDateTime(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(EXIF_IFD_KEY, Arrays.asList(DATETIME_ORIGINAL_FIELD, CREATE_DATE_FIELD));
        locations.put(EXIF_KEY, List.of(DATETIME_ORIGINAL_FIELD));
        locations.put(XMP_XMP_KEY, List.of(CREATE_DATE_FIELD));
        locations.put(XMP_KEY, List.of(DATETIME_ORIGINAL_FIELD));
        locations.put(XMP_EXIF_KEY, List.of(DATETIME_ORIGINAL_FIELD));
        locations.put(COMPOSITE_KEY, Arrays.asList(DATETIME_ORIGINAL_FIELD, "DigitalCreationDateTime", "SubSecCreateDate", "SubSecDateTimeOriginal"));
        locations.put(XMP_PHOTOSHOP_KEY, List.of("DateCreated"));
        return extractJsonString(jsonObject, locations);
    }

    /**
     * Extract the fraction of second of the original time of creation from the given metadata in JSON format
     *
     * @param jsonObject Extracted JSON formatted metadata (from @MetadataTools.getMetadataAsJSON())
     * @return String value retrieved, or null if none were found
     */

    public static int extractOriginalSecondFraction(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(EXIF_IFD_KEY, Arrays.asList("SubSecTimeOriginal", "SubSecTimeDigitized"));
        var fraction = extractJsonNumber(jsonObject, locations);

        if (fraction != null) {
            return fraction.intValue();
        }

        return 0;
    }

    /**
     * Extract the document ID from the given metadata in JSON format
     *
     * @param jsonObject Extracted JSON formatted metadata (from @MetadataTools.getMetadataAsJSON())
     * @return String value retrieved, or null if none were found
     */
    public static String extractOriginalDocumentId(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(XMP_XMPMM_KEY, Arrays.asList("OriginalDocumentID", "DocumentID", "InstanceID", "DerivedFromOriginalDocumentID"));
        locations.put(XMP_KEY, Arrays.asList("OriginalDocumentID", "DocumentID", "InstanceID", "DerivedFromOriginalDocumentID"));
        return extractJsonString(jsonObject, locations);
    }

    /**
     * Extract the list of subjects/keywords from the given metadata in JSON format
     *
     * @param jsonObject Extracted JSON formatted metadata (from @MetadataTools.getMetadataAsJSON())
     * @return String value retrieved, or null if none were found
     */
    public static List<String> extractSubjects(JSONObject jsonObject) {
        var subjectList = new LinkedHashSet<String>();
        addSubjects(jsonObject, XMP_KEY, "Subject", subjectList);
        addSubjects(jsonObject, XMP_DC_KEY, "Subject", subjectList);
        addSubjects(jsonObject, XMP_LR_KEY, "HierarchicalSubject", subjectList);
        addSubjects(jsonObject, XMP_LR_KEY, "WeightedFlatSubject", subjectList);
        addSubjects(jsonObject, IPTC_KEY, IPTC_KEYWORD_FIELD, subjectList);
        return subjectList.stream()
            .toList();
    }

    private static void addSubjects(JSONObject jsonObject, String group, String key, Set<String> subjects) {
        if (jsonObject == null
            || !jsonObject.has(group)) {
            return;
        }

        var groupObject = jsonObject.optJSONObject(group);
        if (groupObject == null
            || !groupObject.has(key)) {
            return;
        }

        var value = groupObject.opt(key);
        if (value instanceof JSONArray array) {
            for (var item : array) {
                addSubject(item, subjects);
            }
        } else {
            addSubject(value, subjects);
        }
    }

    private static void addSubject(Object value, Set<String> subjects) {
        if (value != null
            && !(value instanceof JSONObject)) {
            var subject = Objects.toString(value, "")
                .trim();
            if (!subject.isBlank()) {
                subjects.add(subject);
            }
        }
    }

    public static String extractRightsHolder(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(XMP_DC_KEY, List.of("Rights"));
        locations.put(IFD0_KEY, List.of("Copyright"));
        return extractJsonString(jsonObject, locations);
    }

    public static String extractRightsTerms(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(XMP_XMP_RIGHTS_KEY, List.of("UsageTerms"));
        return extractJsonString(jsonObject, locations);
    }

    public static String extractRightsUrl(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(XMP_XMP_RIGHTS_KEY, List.of("WebStatement"));
        return extractJsonString(jsonObject, locations);
    }

    public static String extractCreatorName(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(IFD0_KEY, List.of("Artist"));
        locations.put(XMP_DC_KEY, List.of("Creator"));
        return extractJsonString(jsonObject, locations);
    }

    public static String extractCreatorEmail(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(XMP_IPTC_CORE_KEY, List.of("CreatorWorkEmail"));
        return extractJsonString(jsonObject, locations);
    }

    public static String extractCreatorCountry(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(XMP_IPTC_CORE_KEY, List.of("CreatorCountry"));
        return extractJsonString(jsonObject, locations);
    }

    public static String extractCreatorUrl(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(XMP_IPTC_CORE_KEY, List.of("CreatorWorkURL"));
        return extractJsonString(jsonObject, locations);
    }

    public static String extractLabel(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(XMP_XMP_KEY, List.of("Label"));
        return extractJsonString(jsonObject, locations);
    }

    public static Instant extractGpsTime(JSONObject jsonObject) {
        var locations = new HashMap<String, List<String>>();
        locations.put(COMPOSITE_KEY, List.of("GPSDateTime"));
        var dateTimeStamp = extractJsonString(jsonObject, locations);

        if (dateTimeStamp == null) {
            locations.put(GPS_KEY, List.of("GPSTimeStamp"));
            var timeStamp = extractJsonString(jsonObject, locations);
            locations = new HashMap<>();
            locations.put(GPS_KEY, List.of("GPSDateStamp"));
            var dateStamp = extractJsonString(jsonObject, locations);
            // Combine date and time to a single string and parse it as a UTC datetime
            if (dateStamp != null
                && !dateStamp.isBlank()
                && timeStamp != null
                && !timeStamp.isBlank()) {
                dateTimeStamp = dateStamp + " " + timeStamp + "Z";
            }
        }

        return dateTimeParser(dateTimeStamp);
    }

    public static GpsData extractGpsData(JSONObject jsonObject) {
        var gpsLocationEntity = new GpsData();

        var locations = new HashMap<String, List<String>>();
        // First attempt to fetch the GPSPosition
        locations.put(COMPOSITE_KEY, List.of("GPSLocation"));
        var gpsLocationString = extractJsonString(jsonObject, locations);

        if (gpsLocationString != null
            && !gpsLocationString.isBlank()
            && gpsLocationString.contains(",")) {
            // GPSPosition is in format "60 deg 10' 30.00\" N, 24 deg 58' 00.0\" E"
            var locationParts = gpsLocationString.split(",");

            if (locationParts.length == 2) {
                // Extract latitude (first part)
                var latitudePart = locationParts[0].trim();
                var latitudeResult = extractCoordinateWithRef(latitudePart);

                // Extract longitude (second part)
                var longitudePart = locationParts[1].trim();
                var longitudeResult = extractCoordinateWithRef(longitudePart);

                // Set the values to the entity
                if (latitudeResult != null) {
                    gpsLocationEntity.setLatitudeRef(latitudeResult.getKey());
                    gpsLocationEntity.setLatitude(latitudeResult.getValue());
                }

                if (longitudeResult != null) {
                    gpsLocationEntity.setLongitudeRef(longitudeResult.getKey());
                    gpsLocationEntity.setLongitude(longitudeResult.getValue());
                }
            }
        } else {
            // Try to get latitude and longitude from separate fields
            var latitudeResult = extractSingleCoordinate(jsonObject, "GPSLatitude", "GPSLatitudeRef");
            var longitudeResult = extractSingleCoordinate(jsonObject, "GPSLongitude", "GPSLongitudeRef");

            if (latitudeResult != null) {
                gpsLocationEntity.setLatitudeRef(latitudeResult.getKey());
                gpsLocationEntity.setLatitude(latitudeResult.getValue());
            }

            if (longitudeResult != null) {
                gpsLocationEntity.setLongitudeRef(longitudeResult.getKey());
                gpsLocationEntity.setLongitude(longitudeResult.getValue());
            }
        }
        // GPS altitude, can be in form of "130 m" or "7 m Above Sea Level"
        locations = new HashMap<>();
        locations.put(GPS_KEY, List.of("GPSAltitude"));
        locations.put(COMPOSITE_KEY, List.of("GPSAltitude"));
        var altitudeString = extractJsonString(jsonObject, locations);

        if (altitudeString != null
            && !altitudeString.isBlank()) {
            var altitudeParts = altitudeString.split(" ");

            if (altitudeParts.length > 0) {
                try {
                    var altitudeValue = Double.parseDouble(altitudeParts[0].trim());
                    gpsLocationEntity.setAltitude(altitudeValue);
                } catch (NumberFormatException e) {
                    log.error("Failed to parse GPS altitude: {}", altitudeString, e);
                }
            }
        }

        // GPS satellite count, seems to be given in a zero-filled format like "03"
        locations = new HashMap<>();
        locations.put(GPS_KEY, List.of("GPSSatellites"));

        try {
            var satelliteString = extractJsonString(jsonObject, locations);
            if (satelliteString != null
                && !satelliteString.isBlank()) {
                try {
                    var satelliteCount = Integer.parseInt(satelliteString.trim());
                    gpsLocationEntity.setSatelliteCount(satelliteCount);
                } catch (NumberFormatException e) {
                    log.error("Failed to parse GPS satellite count: {}", satelliteString, e);
                }
            }
        } catch (JSONException e) {
            log.warn("Failed to parse GPS satellite count as string. Attempting to get number instead");
            var satelliteCount = extractJsonNumber(jsonObject, locations);

            if (satelliteCount != null) {
                gpsLocationEntity.setSatelliteCount(satelliteCount.intValue());
            }
        }

        // GPS direction, degrees with decimal, example 212.1
        locations = new HashMap<>();
        locations.put(GPS_KEY, List.of("GPSImgDirection"));
        var directionDouble = extractJsonNumber(jsonObject, locations);

        if (directionDouble != null) {
            try {
                var directionValue = directionDouble.doubleValue();
                gpsLocationEntity.setDirection(directionValue);
            } catch (NumberFormatException e) {
                log.error("Failed to parse GPS direction: {}", directionDouble, e);
            }
        }
        // Then we need to extract the location data, which is spread over multiple locations
        // Country, country code, e.g. "NL"
        locations = new HashMap<>();
        locations.put(XMP_IPTC_CORE_KEY, List.of("Country"));
        var countryCode = extractJsonString(jsonObject, locations);

        if (countryCode != null
            && !countryCode.isBlank()) {
            gpsLocationEntity.setCountry(countryCode);
        } else {
            // Country, full text localizer, e.g. "Alankomaat"
            locations = new HashMap<>();
            locations.put(XMP_PHOTOSHOP_KEY, List.of("Country"));
            var country = extractJsonString(jsonObject, locations);

            if (country != null
                && !country.isBlank()) {
                gpsLocationEntity.setCountry(country);
            }
        }
        // Province or state, e.g. "Uusimaa"
        locations = new HashMap<>();
        locations.put(XMP_PHOTOSHOP_KEY, List.of("State"));
        var state = extractJsonString(jsonObject, locations);

        if (state != null
            && !state.isBlank()) {
            gpsLocationEntity.setState(state);
        }
        // City, e.g. "Helsinki"
        locations = new HashMap<>();
        locations.put(XMP_PHOTOSHOP_KEY, List.of("City"));
        var city = extractJsonString(jsonObject, locations);
        if (city != null
            && !city.isBlank()) {
            gpsLocationEntity.setCity(city);
        }
        // Street, e.g. "Mannerheimintie". The location may also be just a number, e.g. "4" so we may get an exception here
        try {
            locations = new HashMap<>();
            locations.put(XMP_IPTC_CORE_KEY, List.of("Location"));
            var street = extractJsonString(jsonObject, locations);

            if (street != null
                && !street.isBlank()) {
                gpsLocationEntity.setStreet(street);
            }
        } catch (JSONException e) {
            log.warn("Failed to parse GPS location as string: {}", e.getMessage());
        }

        return gpsLocationEntity;
    }

    /**
     * Extracts a coordinate value and its reference from separate metadata fields
     *
     * @param jsonObject    The metadata JSON object
     * @param coordinateKey The key for the coordinate value
     * @param refKey        The key for the coordinate reference
     * @return A Map.Entry containing the reference character and coordinate value
     */
    private static Map.Entry<Character, BigDecimal> extractSingleCoordinate(JSONObject jsonObject, String coordinateKey, String refKey) {
        // Get reference (N/S/E/W)
        var locations = new HashMap<String, List<String>>();
        locations.put(GPS_KEY, List.of(refKey));
        var refString = extractJsonString(jsonObject, locations);
        Character ref = refString != null
                        && !refString.isBlank() ? refString.charAt(0) : null;

        // Get coordinate string
        locations = new HashMap<>();
        locations.put(GPS_KEY, List.of(coordinateKey));
        locations.put(COMPOSITE_KEY, List.of(coordinateKey));
        var coordinateString = extractJsonString(jsonObject, locations);

        // If no reference was found but coordinate has one at the end, extract it
        if (ref == null
            && coordinateString != null
            && !coordinateString.isBlank()
            && coordinateString.substring(coordinateString.length() - 1)
                .matches("[NSEW]")) {
            var result = extractCoordinateWithRef(coordinateString);
            ref = result.getKey();
            BigDecimal value = result.getValue();
            return entry(ref, value);
        }

        // Otherwise convert the coordinate with the provided reference
        BigDecimal value = convertGpsCoordinateStringToBigDecimal(coordinateString, ref);

        if (value == null) {
            log.warn("Failed to extract coordinate for key {} with reference {}", coordinateKey, ref);
            return null;
        }

        return entry(ref, value);
    }

    /**
     * Extracts a coordinate and its reference from a single string
     *
     * @param coordinateString The coordinate string possibly containing a reference at the end
     * @return A Map.Entry containing the reference character and coordinate value
     */
    private static Map.Entry<Character, BigDecimal> extractCoordinateWithRef(String coordinateString) {
        if (coordinateString == null
            || coordinateString.isBlank()) {
            return null;
        }

        // Check if the last character is a cardinal point (N/S/E/W)
        char lastChar = coordinateString.charAt(coordinateString.length() - 1);
        Character ref = null;
        String coordStr = coordinateString;

        if (lastChar == 'N'
            || lastChar == 'S'
            || lastChar == 'E'
            || lastChar == 'W') {
            ref = lastChar;
            coordStr = coordinateString.substring(0, coordinateString.length() - 1)
                .trim();
        }

        if (ref == null) {
            log.warn("No cardinal point found in coordinate string: {}", coordinateString);
            return null;
        }

        BigDecimal value = convertGpsCoordinateStringToBigDecimal(coordStr, ref);
        return entry(ref, value);
    }

    public static BigDecimal convertGpsCoordinateStringToBigDecimal(String coordinateString, Character cardinalPoint) {
        if (cardinalPoint == null) {
            return null;
        }

        switch (cardinalPoint) {
            case 'N' -> {
                // North latitude, positive value
                if (coordinateString != null
                    && !coordinateString.isBlank()) {
                    try {
                        return convertDmsToBigDecimal(coordinateString);
                    } catch (Exception e) {
                        log.error("Failed to parse GPS latitude: {} {}", coordinateString, cardinalPoint, e);
                    }
                }
            }
            case 'S' -> {
                // South latitude, negative value
                if (coordinateString != null
                    && !coordinateString.isBlank()) {
                    try {
                        return convertDmsToBigDecimal(coordinateString).negate();
                    } catch (Exception e) {
                        log.error("Failed to parse GPS latitude: {} {}", coordinateString, cardinalPoint, e);
                    }
                }
            }
            case 'E' -> {
                // East longitude, positive value
                if (coordinateString != null
                    && !coordinateString.isBlank()) {
                    try {
                        return convertDmsToBigDecimal(coordinateString);
                    } catch (Exception e) {
                        log.error("Failed to parse GPS longitude: {} {}", coordinateString, cardinalPoint, e);
                    }
                }
            }
            case 'W' -> {
                // West longitude, negative value
                if (coordinateString != null
                    && !coordinateString.isBlank()) {
                    try {
                        return convertDmsToBigDecimal(coordinateString).negate();
                    } catch (Exception e) {
                        log.error("Failed to parse GPS longitude: {} {}", coordinateString, cardinalPoint, e);
                    }
                }
            }
            default -> log.warn("Unknown latitude reference: {}", cardinalPoint);
        }

        return null;
    }

    private static BigDecimal convertDmsToBigDecimal(String latitudeString) {
        // GPS coordinates are in DMS format, e.g. "60 deg 10' 30.00\""
        // We need to convert it to decimal format
        var dmsParts = latitudeString.split("[^0-9.+-]+");

        if (dmsParts.length < 3) {
            throw new IllegalArgumentException("Invalid DMS format: " + latitudeString);
        }

        try {
            BigDecimal degrees = new BigDecimal(dmsParts[0]);
            BigDecimal minutes = new BigDecimal(dmsParts[1]);
            BigDecimal seconds = new BigDecimal(dmsParts[2]);

            // Convert to decimal degrees: degrees + (minutes/60) + (seconds/3600)
            BigDecimal minutesFraction = minutes.divide(new BigDecimal("60"), GPS_DECIMAL_PRECISION, RoundingMode.HALF_UP);
            BigDecimal secondsFraction = seconds.divide(new BigDecimal("3600"), GPS_DECIMAL_PRECISION, RoundingMode.HALF_UP);

            return degrees.add(minutesFraction)
                .add(secondsFraction)
                .setScale(GPS_DECIMAL_PRECISION, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number in DMS format: " + latitudeString, e);
        }
    }

    public static List<String> extractJsonArray(JSONObject jsonObject, Map<String, List<String>> locations) {
        for (Map.Entry<String, List<String>> location : locations.entrySet()) {
            for (String key : location.getValue()) {
                if (jsonObject.has(location.getKey())
                    && jsonObject.getJSONObject(location.getKey())
                        .has(key)) {
                    Object targetObject = jsonObject.getJSONObject(location.getKey())
                        .opt(key);

                    if (targetObject instanceof JSONArray targetArray) {
                        try {
                            return targetArray.toList()
                                .stream()
                                .map(o -> Objects.toString(o, null))
                                .toList();
                        } catch (Exception e) {
                            log.error("Failed to convert JSON array for key {}", key, e);
                        }
                    } else {
                        // Not an array: attempt to treat it as a single scalar value
                        if (targetObject != null
                            && !(targetObject instanceof JSONObject)) { // Ignore nested objects
                            String value = Objects.toString(targetObject, null);

                            if (value != null
                                && !value.isBlank()) {
                                log.debug("Key {} under {} is not a JSON array, but a single value, returning a one-item array", key, location.getKey());
                                return new ArrayList<>(List.of(value.trim()));
                            }
                        }
                        var targetSimpleName = targetObject == null ? "null"
                                : targetObject.getClass()
                                    .getSimpleName();
                        log.warn("Key {} under {} is not a JSON array (type: {})", key, location.getKey(), targetSimpleName);
                    }
                }
            }
        }

        return new ArrayList<>();
    }

    // Per-type fields from the grouped JSON ---------------------------------------------------------------------------------------------------

    /**
     * The first value of any of the tag names in the grouped (or flat) ExifTool JSON, searching the groups in the order ExifTool printed them; the match is
     * case-insensitive like a {@code -TAG} argument. Nested objects are skipped, arrays are joined with a comma.
     *
     * @return the value as a string, or {@code null} when none of the tags is present
     */
    public static String firstValue(JSONObject jsonObject, String... tagNames) {
        var scalar = firstScalar(jsonObject, tagNames);
        if (scalar == null) {
            return null;
        }
        if (scalar instanceof JSONArray array) {
            return array.toList()
                .stream()
                .map(item -> Objects.toString(item, ""))
                .collect(Collectors.joining(", "));
        }
        return Objects.toString(scalar, null);
    }

    private static Object firstScalar(JSONObject jsonObject, String... tagNames) {
        if (jsonObject == null) {
            return null;
        }
        for (var tagName : tagNames) {
            // Flat -j output: the tags are on the root level
            var flat = findKeyIgnoreCase(jsonObject, tagName);
            if (flat != null
                && !(flat instanceof JSONObject)) {
                return flat;
            }
            // Grouped -g1 output: look into every group
            for (var groupName : jsonObject.keySet()) {
                var group = jsonObject.optJSONObject(groupName);
                if (group == null) {
                    continue;
                }
                var value = findKeyIgnoreCase(group, tagName);
                if (value != null
                    && !(value instanceof JSONObject)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Object findKeyIgnoreCase(JSONObject object, String key) {
        if (object.has(key)) {
            return object.opt(key);
        }
        for (var candidate : object.keySet()) {
            if (candidate.equalsIgnoreCase(key)) {
                return object.opt(candidate);
            }
        }
        return null;
    }

    private static Integer firstInteger(JSONObject jsonObject, String... tagNames) {
        var scalar = firstScalar(jsonObject, tagNames);
        if (scalar instanceof Number number) {
            return number.intValue();
        }
        return parseIntegerWithUnits(Objects.toString(scalar, null));
    }

    private static boolean firstBoolean(JSONObject jsonObject, String... tagNames) {
        var value = firstValue(jsonObject, tagNames);
        return value != null
               && (value.equalsIgnoreCase("true")
                   || value.equalsIgnoreCase("yes")
                   || value.equals("1"));
    }

    /** Video frame rate ({@code VideoFrameRate}), 0 when the file carries none. */
    public static double extractFrameRate(JSONObject jsonObject) {
        var parsed = parseDoubleSafe(firstValue(jsonObject, "VideoFrameRate"));
        return parsed == null ? 0 : parsed;
    }

    /** Playing time of an audio or video file ({@code Duration}), {@link Duration#ZERO} when unknown. */
    public static Duration extractAudioVideoDuration(JSONObject jsonObject) {
        return durationFromString(firstValue(jsonObject, "Duration"));
    }

    /**
     * Audio bit rate in kbps from {@code AudioBitrate}, {@code AvgBitrate}, {@code NominalBitrate} or {@code BitRate}; when none is present it is calculated
     * from the duration and the file size.
     */
    public static int extractAudioBitRate(JSONObject jsonObject, long fileSizeBytes) {
        return parseAudioBitRateKbps(jsonObject, fileSizeBytes);
    }

    /** Audio sample rate ({@code AudioSampleRate} or {@code SampleRate}), 0 when unknown. */
    public static int extractAudioSampleRate(JSONObject jsonObject) {
        var parsed = firstInteger(jsonObject, "AudioSampleRate");
        if (parsed != null
            && parsed > 0) {
            return parsed;
        }
        parsed = firstInteger(jsonObject, "SampleRate");
        return parsed != null ? parsed : 0;
    }

    /** Audio codec ({@code AudioCodec}), empty when unknown. */
    public static String extractAudioCodec(JSONObject jsonObject) {
        return Objects.requireNonNullElse(firstValue(jsonObject, "AudioCodec"), "");
    }

    /** Number of audio channels ({@code AudioChannels} or {@code Channels}), 0 when unknown. */
    public static int extractAudioChannels(JSONObject jsonObject) {
        var parsed = firstInteger(jsonObject, "AudioChannels");
        if (parsed != null
            && parsed > 0) {
            return parsed;
        }
        parsed = firstInteger(jsonObject, "Channels");
        return parsed != null ? parsed : 0;
    }

    /** Page count of a document ({@code PageCount}), 0 when unknown. */
    public static int extractDocumentPageCount(JSONObject jsonObject) {
        var parsed = firstInteger(jsonObject, "PageCount");
        return parsed != null ? parsed : 0;
    }

    /** ExifTool's file type name ({@code FileType}), empty when unknown. */
    public static String extractDocumentFormat(JSONObject jsonObject) {
        return Objects.requireNonNullElse(firstValue(jsonObject, "FileType"), "");
    }

    /** Layer count of a vector image ({@code Layers}), 0 when unknown. */
    public static int extractVectorLayersCount(JSONObject jsonObject) {
        var parsed = firstInteger(jsonObject, "Layers");
        return parsed != null ? parsed : 0;
    }

    /** Pixel size from {@code ImageWidth} and {@code ImageHeight}, or {@code null} when either is missing. */
    public static Dimension extractXYResolution(JSONObject jsonObject) {
        var width = firstInteger(jsonObject, "ImageWidth");
        var height = firstInteger(jsonObject, "ImageHeight");
        if (width == null
            || height == null) {
            return null;
        }
        return new Dimension(width, height);
    }

    /** Whether an icon is scalable ({@code Scalable}). */
    public static boolean extractIconIsScalable(JSONObject jsonObject) {
        return firstBoolean(jsonObject, "Scalable");
    }

    /** Font family ({@code FontFamily}), empty when unknown. */
    public static String extractFontFamily(JSONObject jsonObject) {
        return Objects.requireNonNullElse(firstValue(jsonObject, "FontFamily"), "");
    }

    /** Font weight ({@code FontWeight}), empty when unknown. */
    public static String extractFontWeight(JSONObject jsonObject) {
        return Objects.requireNonNullElse(firstValue(jsonObject, "FontWeight"), "");
    }

    /** Font style ({@code FontStyle}), empty when unknown. */
    public static String extractFontStyle(JSONObject jsonObject) {
        return Objects.requireNonNullElse(firstValue(jsonObject, "FontStyle"), "");
    }

    /** Compression method of an archive ({@code Compression}, {@code ZipCompression} or {@code CompressionMethod}), empty when unknown. */
    public static String extractArchiveCompressionMethod(JSONObject jsonObject) {
        return Objects.requireNonNullElse(firstValue(jsonObject, "Compression", "ZipCompression", "CompressionMethod"), "");
    }

    /** Uncompressed size of an archive ({@code UncompressedSize} or {@code ZipUncompressedSize}), 0 when unknown. */
    public static long extractArchiveUncompressedSize(JSONObject jsonObject) {
        var scalar = firstScalar(jsonObject, "UncompressedSize", "ZipUncompressedSize");
        if (scalar instanceof Number number) {
            return number.longValue();
        }
        var parsed = parseDoubleSafe(Objects.toString(scalar, null));
        return parsed == null ? 0 : Math.round(parsed);
    }

    /** Number of entries in an archive ({@code ContentCount}), 0 when unknown. */
    public static int extractArchiveContentCount(JSONObject jsonObject) {
        var parsed = firstInteger(jsonObject, "ContentCount");
        return parsed != null ? parsed : 0;
    }

    /** Whether an archive is encrypted ({@code Encrypted}). */
    public static boolean extractArchiveIsEncrypted(JSONObject jsonObject) {
        return firstBoolean(jsonObject, "Encrypted");
    }

    /**
     * Audio bit rate in kbps of a flat ExifTool {@code -j} output (an array with one object), see {@link #extractAudioBitRate(JSONObject, long)}.
     */
    static int parseAudioBitRateKbps(String exifJsonOutput, long fallbackFileSizeBytes) {
        JSONObject jsonObject = null;
        if (exifJsonOutput != null
            && !exifJsonOutput.isBlank()) {
            try {
                jsonObject = metadataToJsonObject(exifJsonOutput);
            } catch (JSONException e) {
                log.warn("Could not parse the ExifTool output for the bit rate: {}", e.getMessage());
            }
        }
        return parseAudioBitRateKbps(jsonObject, fallbackFileSizeBytes);
    }

    static int parseAudioBitRateKbps(JSONObject jsonObject, long fallbackFileSizeBytes) {
        var primary = firstInteger(jsonObject, "AudioBitrate");
        if (primary != null
            && primary > 0) {
            return primary;
        }

        for (var tag : List.of("AvgBitrate", "NominalBitrate", "BitRate")) {
            var parsed = firstInteger(jsonObject, tag);
            if (parsed != null
                && parsed > 0) {
                return parsed;
            }
        }

        var duration = durationFromString(firstValue(jsonObject, "Duration"));
        if (duration.isZero()
            || duration.isNegative()
            || fallbackFileSizeBytes <= 0) {
            return 0;
        }

        double seconds = duration.toMillis() / 1000.0;
        if (seconds <= 0) {
            return 0;
        }

        var calculated = (int) Math.round((fallbackFileSizeBytes * 8.0) / seconds / 1000.0);
        return Math.max(calculated, 0);
    }

    public static JSONObject metadataToJsonObject(String metadata) {
        var jsonArray = new JSONArray(metadata);

        if (jsonArray.isEmpty()) {
            log.error("Failed to parse the metadata JSON from\n{}", metadata);
            return null;
        }

        return jsonArray.getJSONObject(0);
    }

    private static String extractJsonString(JSONObject jsonObject, Map<String, List<String>> locations) {
        for (Map.Entry<String, List<String>> location : locations.entrySet()) {
            for (String key : location.getValue()) {
                if (jsonObject.has(location.getKey())
                    && jsonObject.getJSONObject(location.getKey())
                        .has(key)) {
                    // Use opt() so that numeric values (e.g. a track title stored as an integer)
                    // are converted to String instead of throwing JSONException.
                    return Objects.toString(jsonObject.getJSONObject(location.getKey())
                        .opt(key), null);
                }

                // Also support flat exiftool JSON where fields are on root level.
                if (jsonObject.has(key)
                    && !(jsonObject.opt(key) instanceof JSONObject)) {
                    return Objects.toString(jsonObject.opt(key), null);
                }
            }
        }

        return null;
    }

    private static Number extractJsonNumber(JSONObject jsonObject, Map<String, List<String>> locations) {
        for (Map.Entry<String, List<String>> location : locations.entrySet()) {
            for (String key : location.getValue()) {
                if (jsonObject.has(location.getKey())
                    && jsonObject.getJSONObject(location.getKey())
                        .has(key)) {
                    Number number;
                    try {
                        number = jsonObject.getJSONObject(location.getKey())
                            .getNumber(key);
                        return number;
                    } catch (JSONException e) {
                        log.warn("Failed to retrieve JSON number from location {}, trying to get it as String instead", key);
                        var stringValue = jsonObject.getJSONObject(location.getKey())
                            .getString(key);

                        try {
                            return Double.valueOf(stringValue);
                        } catch (NumberFormatException ex) {
                            log.error("Key {} exists but can not be parsed as number", key);
                        }
                    }
                }

                if (jsonObject.has(key)
                    && !(jsonObject.opt(key) instanceof JSONObject)) {
                    var flat = jsonObject.opt(key);
                    if (flat instanceof Number n) {
                        return n;
                    }
                    var parsed = parseDoubleSafe(Objects.toString(flat, null));
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }

        return null;
    }

    private static Object extractJsonScalar(JSONObject jsonObject, Map<String, List<String>> locations) {
        for (Map.Entry<String, List<String>> location : locations.entrySet()) {
            for (String key : location.getValue()) {
                if (jsonObject.has(location.getKey())
                    && jsonObject.getJSONObject(location.getKey())
                        .has(key)) {
                    var value = jsonObject.getJSONObject(location.getKey())
                        .opt(key);
                    if (!(value instanceof JSONObject)
                        && value != null) {
                        return value;
                    }
                }

                if (jsonObject.has(key)
                    && !(jsonObject.opt(key) instanceof JSONObject)) {
                    return jsonObject.opt(key);
                }
            }
        }

        return null;
    }

    public static Duration durationFromString(String durationStr) {
        if (durationStr == null
            || durationStr.isBlank()) {
            return Duration.ZERO;
        }

        var normalized = durationStr.trim();

        // Exiftool may emit "0:02:50 (approx)".
        var approxIndex = normalized.indexOf(' ');
        if (approxIndex > 0) {
            normalized = normalized.substring(0, approxIndex)
                .trim();
        }

        if (normalized.contains(":")) {
            var parts = normalized.split(":");
            try {
                double seconds;
                if (parts.length == 3) {
                    seconds = Double.parseDouble(parts[0].trim()) * 3600 + Double.parseDouble(parts[1].trim()) * 60 + Double.parseDouble(parts[2].trim());
                } else if (parts.length == 2) {
                    seconds = Double.parseDouble(parts[0].trim()) * 60 + Double.parseDouble(parts[1].trim());
                } else {
                    return Duration.ZERO;
                }
                return clampDurationToSchema(Duration.ofMillis(Math.round(seconds * 1000)));
            } catch (NumberFormatException e) {
                log.warn("Could not parse duration string: {}", durationStr);
                return Duration.ZERO;
            }
        }

        var numericPart = normalized.replaceAll("(?i)\\s*(seconds?|sec|s)$", "")
            .trim();
        var seconds = parseDoubleSafe(numericPart);
        if (seconds == null
            || seconds < 0) {
            return Duration.ZERO;
        }

        return clampDurationToSchema(Duration.ofMillis(Math.round(seconds * 1000)));
    }

    private static Duration clampDurationToSchema(Duration duration) {
        if (duration == null
            || duration.isNegative()) {
            return Duration.ZERO;
        }
        if (duration.getSeconds() > MAX_DURATION_SECONDS) {
            log.warn("Duration {} exceeds schema max {} seconds, clamping", duration, MAX_DURATION_SECONDS);
            return Duration.ofSeconds(MAX_DURATION_SECONDS);
        }
        return duration;
    }

    public static Instant dateTimeParser(String dateTimeString) {
        if (dateTimeString == null
            || dateTimeString.isBlank()) {
            return null;
        }

        var trimmed = dateTimeString.trim();

        // Some metadata providers return just a year (e.g. "1991").
        if (trimmed.matches("\\d{4}")) {
            try {
                var yearInstant = LocalDate.of(Integer.parseInt(trimmed), 1, 1)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant();
                log.debug("Parsed year-only date time string '{}' to Instant: {}", dateTimeString, yearInstant);
                return yearInstant;
            } catch (Exception e) {
                log.error("Failed to parse year-only date time string: {}", dateTimeString, e);
                return null;
            }
        }

        // Some malformed metadata uses yyyy:dd:MM without time (e.g. "2005:28:07").
        if (trimmed.matches("\\d{4}:\\d{2}:\\d{2}")) {
            try {
                var yearDayMonth = LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("yyyy:dd:MM"));
                var parsed = yearDayMonth.atStartOfDay(ZoneId.systemDefault())
                    .toInstant();
                log.debug("Parsed year-day-month date time string '{}' to Instant: {}", dateTimeString, parsed);
                return parsed;
            } catch (DateTimeParseException e) {
                log.error("Failed to parse year-day-month date time string: {}", dateTimeString, e);
                return null;
            }
        }

        var normalized = trimmed;
        var offsetStart = Math.max(trimmed.lastIndexOf('+'), trimmed.lastIndexOf('-'));
        if (offsetStart > 0) {
            var offset = trimmed.substring(offsetStart);
            if (offset.matches("[+-]\\d:\\d{2}")) {
                normalized = trimmed.substring(0, offsetStart) + offset.charAt(0) + "0" + offset.substring(1);
            }
        }

        var formatter = new DateTimeFormatterBuilder()
            // Date
            .appendPattern("yyyy:MM:dd HH:mm")
            // Optional seconds
            .optionalStart()
            .appendPattern(":ss")
            .optionalEnd()
            // Optional fractional seconds (from 1 to 9 digits)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            // Optional offset like +02:00
            .optionalStart()
            .appendOffset("+HH:mm", "Z")
            .optionalEnd()
            .toFormatter()
            .withZone(ZoneId.systemDefault());

        try {
            var timeStamp = formatter.parse(normalized, Instant::from);
            log.debug("Parsed date time string '{}' to Instant: {}", dateTimeString, timeStamp);
            return timeStamp;
        } catch (DateTimeParseException e) {
            log.error("Failed to parse date time string: {}", dateTimeString, e);
            return null;
        }
    }

    /**
     * Scan a list of metadata entries (two-tier: group/key/value) and the rights and creator fallback values to collect common camera/image metadata into a
     * JSON array (one element) using common EXIF/XMP keys. Returned JSON is an array with a single object, compatible with exiftool -json ingestion.
     */
    public static String collectStandardMetadataAsJson(List<MetadataEntry> metadataEntities, RightsAndCreator fileEntity) {
        var result = new LinkedHashMap<String, Object>();
        if (metadataEntities == null
            || metadataEntities.isEmpty()) {
            // still include rights/creator if present
            putFileRightsAndCreatorForExif(result, fileEntity);
            return writeJsonArray(result);
        }

        // Build quick lookup: Map<groupLower, Map<keyLower, value>>
        var index = metadataEntities.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(me -> normalizeName(me.group()), LinkedHashMap::new,
                    Collectors.toMap(me -> normalizeName(me.key()), MetadataEntry::value, MetadataJson::firstNonNull)));

        // Helper to fetch by prioritized (group,key) pairs
        java.util.function.BiFunction<List<String>, List<String>, String> findFirst = (groups, keys) -> {
            for (String g : groups) {
                var gi = index.get(normalizeName(g));
                if (gi == null) {
                    continue;
                }
                for (String k : keys) {
                    var v = gi.get(normalizeName(k));
                    if (v != null
                        && !v.isBlank()) {
                        return v;
                    }
                }
            }
            return null;
        };

        // collect candidate values using common EXIF/XMP key names
        String make = findFirst.apply(List.of("XMP-exif", "IFD0", "EXIF", "ExifIFD", "Composite"), List.of("Make", "Maker", "Make"));
        String model = findFirst.apply(List.of("XMP-exif", "IFD0", "EXIF", "ExifIFD", "Composite"), List.of("Model"));
        String artist = findFirst.apply(List.of("IFD0", "XMP-dc", "XMP-exif"), List.of("Artist", "Creator"));
        String copyright = findFirst.apply(List.of("IFD0", "XMP-xmpRights", "XMP-dc", "Composite"), List.of("Copyright", "CopyrightNotice", "Rights"));
        String exposureTime = findFirst.apply(List.of("ExifIFD", "EXIF", "Composite"), List.of("ExposureTime", "ExposureTime"));
        String fNumberStr = findFirst.apply(List.of("ExifIFD", "Composite", "IFD0"), List.of("FNumber", "Aperture", "ApertureValue"));
        String shutterSpeed = findFirst.apply(List.of("Composite", "ExifIFD", "EXIF", "IFD0"), List.of("ShutterSpeed", "ShutterSpeedValue", "ExposureTime"));
        String focalLength = findFirst.apply(List.of("ExifIFD", "Composite", "IFD0"), List.of("FocalLength", "FocalLength35efl", "FocalLengthIn35mmFormat"));
        String originalDocId = findFirst.apply(List.of("XMP-xmpMM", "XMP-xmp", "XMP", "Composite"),
                List.of("OriginalDocumentID", "DocumentID", "InstanceID", "DerivedFromOriginalDocumentID"));
        String webStatement = findFirst.apply(List.of("XMP-xmpRights", "XMP-xmp", "XMP", "IFD0"),
                List.of("WebStatement", "WebStatement", "WebStatement", "WebStatement"));
        String usageTerms = findFirst.apply(List.of("XMP-xmpRights", "XMP-xmp", "XMP"), List.of("UsageTerms", "UsageTerms", "UsageTerms"));
        String creatorEmail = findFirst.apply(List.of("XMP-iptcCore", "XMP-dc", "IFD0"), List.of("CreatorWorkEmail", "Creator", "CreatorWorkEmail"));
        String creatorCountry = findFirst.apply(List.of("XMP-iptcCore", "IFD0"), List.of("CreatorCountry", "CreatorCountry"));
        String creatorUrl = findFirst.apply(List.of("XMP-iptcCore", "XMP-dc"), List.of("CreatorWorkURL", "CreatorWorkURL"));

        String isoStr = findFirst.apply(List.of("Composite", "ExifIFD", "EXIF", "IFD0"), List.of("ISO", "ISOSpeed", "CameraISO", "ISO"));
        String bitsPerSampleStr = findFirst.apply(List.of("SUBIFD", "File", "PNG", "SubIFD1", "SubIFD2", "SubIFD"),
                List.of("BitsPerSample", "BitDepth", "BitsPerSample"));
        String imageSize = findFirst.apply(List.of("Composite", "File", "SubIFD", "SubIFD1", "SubIFD2"), List.of("ImageSize", "ImageSize"));
        if (imageSize == null) {
            String width = findFirst.apply(List.of("File", "SubIFD", "SubIFD1", "SubIFD2"), List.of("ImageWidth", "ImageWidth"));
            String height = findFirst.apply(List.of("File", "SubIFD", "SubIFD1", "SubIFD2"), List.of("ImageHeight", "ImageHeight"));
            if (width != null
                && height != null) {
                imageSize = width.trim() + "x" + height.trim();
            }
        }
        String megapixelsStr = findFirst.apply(List.of("Composite", "EXIF", "IFD0"), List.of("Megapixels", "Megapixels"));
        String circleOfConfusion = findFirst.apply(List.of("Composite", "IFD0"), List.of("CircleOfConfusion"));
        String fov = findFirst.apply(List.of("Composite", "XMP-crs", "XMP-panorama"), List.of("FOV", "FOV", "VirtualFocalLength"));

        // Put values into result using EXIF/XMP keys and typed parsing where sensible
        putIfNotNullObject(result, "Make", make);
        putIfNotNullObject(result, "Model", model);

        // Creator/Artist
        if (artist == null
            || artist.isBlank()) {
            artist = fileEntity != null ? fileEntity.creatorName() : null;
        }
        putIfNotNullObject(result, "Artist", artist);
        putIfNotNullObject(result, "Creator", artist);

        // Copyright / Rights
        if (copyright == null
            || copyright.isBlank()) {
            copyright = fileEntity != null ? fileEntity.rightsHolder() : null;
        }
        putIfNotNullObject(result, "Copyright", copyright);
        putIfNotNullObject(result, "CopyrightNotice", copyright);
        if (copyright != null
            && !copyright.isBlank()) {
            result.put("CopyrightFlag", Boolean.TRUE);
            putIfNotNullObject(result, "Rights", copyright);
        }

        putIfNotNullObject(result, "ExposureTime", exposureTime);
        // FNumber / ApertureValue numeric
        Double fNumber = parseDoubleSafe(fNumberStr);
        if (fNumber != null) {
            result.put("FNumber", fNumber);
            result.put("ApertureValue", fNumber);
            result.put("Aperture", fNumber);
        } else {
            putIfNotNullObject(result, "FNumber", fNumberStr);
            putIfNotNullObject(result, "ApertureValue", fNumberStr);
            putIfNotNullObject(result, "Aperture", fNumberStr);
        }

        putIfNotNullObject(result, "ShutterSpeedValue", shutterSpeed);
        putIfNotNullObject(result, "ShutterSpeed", shutterSpeed);
        putIfNotNullObject(result, "FocalLength", focalLength);
        putIfNotNullObject(result, "OriginalDocumentID", originalDocId);

        // WebStatement / UsageTerms
        if ((webStatement == null
             || webStatement.isBlank())
            && fileEntity != null) {
            webStatement = fileEntity.rightsUrl();
        }
        putIfNotNullObject(result, "WebStatement", webStatement);
        if ((usageTerms == null
             || usageTerms.isBlank())
            && fileEntity != null) {
            usageTerms = safeString(fileEntity.rightsTerms());
        }
        putIfNotNullObject(result, "UsageTerms", usageTerms);

        // Creator contact fields (prefer metadata then the fallback values)
        if ((creatorEmail == null
             || creatorEmail.isBlank())
            && fileEntity != null) {
            creatorEmail = fileEntity.creatorEmail();
        }
        putIfNotNullObject(result, "CreatorWorkEmail", creatorEmail);

        if ((creatorCountry == null
             || creatorCountry.isBlank())
            && fileEntity != null) {
            creatorCountry = fileEntity.creatorCountry();
        }
        putIfNotNullObject(result, "CreatorCountry", creatorCountry);

        if ((creatorUrl == null
             || creatorUrl.isBlank())
            && fileEntity != null) {
            creatorUrl = fileEntity.creatorUrl();
        }
        putIfNotNullObject(result, "CreatorWorkURL", creatorUrl);

        // ISO as number if possible
        Integer iso = parseIntegerSafe(isoStr);
        if (iso != null) {
            result.put("ISO", iso);
        } else {
            putIfNotNullObject(result, "ISO", isoStr);
        }

        // BitsPerSample numeric
        Integer bitsPerSample = parseIntegerSafe(bitsPerSampleStr);
        if (bitsPerSample != null) {
            result.put("BitsPerSample", bitsPerSample);
        } else {
            putIfNotNullObject(result, "BitsPerSample", bitsPerSampleStr);
        }

        // ImageSize
        putIfNotNullObject(result, "ImageSize", imageSize);

        // Megapixels numeric
        Double megapixels = parseDoubleSafe(megapixelsStr);
        if (megapixels != null) {
            result.put("Megapixels", megapixels);
        } else {
            putIfNotNullObject(result, "Megapixels", megapixelsStr);
        }

        putIfNotNullObject(result, "CircleOfConfusion", circleOfConfusion);
        putIfNotNullObject(result, "FOV", fov);
        // Also keep ExposureTime and ShutterSpeed as duplicates
        putIfNotNullObject(result, "Exposure", exposureTime);

        // Ensure we include rights/creator fallback values as well (prefer already set keys)
        putFileRightsAndCreatorForExif(result, fileEntity);

        return writeJsonArray(result);
    }

    // Helper: write single-object-as-array map as JSON string
    private static String writeJsonArray(Map<String, Object> map) {
        try {
            var mapper = new ObjectMapper();
            // produce an array with single element
            return mapper.writeValueAsString(new Object[]{ map });
        } catch (Exception e) {
            log.error("Failed to serialize metadata map to JSON array", e);
            return "[]";
        }
    }

    // Helper: put object value if not null (used for strings and typed values)
    private static void putIfNotNullObject(Map<String, Object> map, String key, Object value) {
        if (value instanceof String s) {
            if (s != null
                && !s.isBlank()) {
                map.put(key, s);
            }
        } else if (value != null) {
            map.put(key, value);
        }
    }

    private static void putFileRightsAndCreatorForExif(Map<String, Object> map, RightsAndCreator fileEntity) {
        if (fileEntity == null) {
            return;
        }
        try {
            var rights = safeString(fileEntity.rightsHolder());
            if (rights != null
                && !rights.isBlank()) {
                // put multiple common keys for compatibility
                map.putIfAbsent("Rights", rights);
                map.putIfAbsent("Copyright", rights);
                map.putIfAbsent("CopyrightNotice", rights);
            }
        } catch (Exception ignored) {
        }
        try {
            var creator = safeString(fileEntity.creatorName());
            if (creator != null
                && !creator.isBlank()) {
                map.putIfAbsent("Creator", creator);
                map.putIfAbsent("Artist", creator);
            }
        } catch (Exception ignored) {
        }
        try {
            var web = safeString(fileEntity.rightsUrl());
            if (web != null
                && !web.isBlank()) {
                map.putIfAbsent("WebStatement", web);
            }
        } catch (Exception ignored) {
        }
        try {
            var email = safeString(fileEntity.creatorEmail());
            if (email != null
                && !email.isBlank()) {
                map.putIfAbsent("CreatorWorkEmail", email);
            }
        } catch (Exception ignored) {
        }
        try {
            var country = safeString(fileEntity.creatorCountry());
            if (country != null
                && !country.isBlank()) {
                map.putIfAbsent("CreatorCountry", country);
            }
        } catch (Exception ignored) {
        }
        try {
            var url = safeString(fileEntity.creatorUrl());
            if (url != null
                && !url.isBlank()) {
                map.putIfAbsent("CreatorWorkURL", url);
            }
        } catch (Exception ignored) {
        }
        try {
            var terms = safeString(fileEntity.rightsTerms());
            if (terms != null
                && !terms.isBlank()) {
                map.putIfAbsent("UsageTerms", terms);
            }
        } catch (Exception ignored) {
        }
    }

    private static String safeString(String s) {
        return s == null ? null : s.trim();
    }

    private static Integer parseIntegerSafe(String s) {
        if (s == null) {
            return null;
        }
        try {
            // remove non-digit characters except leading minus
            var cleaned = s.trim()
                .replaceAll("[^0-9-]", "");
            if (cleaned.isBlank()) {
                return null;
            }
            return Integer.parseInt(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer parseIntegerWithUnits(String s) {
        if (s == null
            || s.isBlank()) {
            return null;
        }

        var trimmed = s.trim();
        try {
            var matcher = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)?")
                .matcher(trimmed);
            if (!matcher.find()) {
                return null;
            }

            var value = Double.parseDouble(matcher.group()
                .replace(',', '.'));
            var lower = trimmed.toLowerCase();

            if (lower.contains("mbps")) {
                value *= 1000.0;
            }

            return (int) Math.round(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseDoubleSafe(String s) {
        if (s == null) {
            return null;
        }
        try {
            // Accept values like "1.7", "1/6" (do not convert fractions), "4.3 mm" => extract numeric part if present
            var trimmed = s.trim();
            // if fraction like 1/6, leave as string (not parseable to double reliably) -> return null
            if (trimmed.matches("\\d+\\s*/\\s*\\d+")) {
                return null;
            }
            // extract first valid number (with decimal)
            var m = trimmed.replaceAll("[^0-9.+-]", "");
            if (m.isBlank()) {
                return null;
            }
            return Double.valueOf(m);
        } catch (Exception e) {
            return null;
        }
    }

    // Helper to normalize group/key names for indexing and lookup
    private static String normalizeName(String s) {
        return (s == null) ? ""
                : s.trim()
                    .toLowerCase();
    }
}
