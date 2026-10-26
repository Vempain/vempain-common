package fi.poltsi.vempain.common.metadata;

import java.math.BigDecimal;
import java.time.Instant;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("checkstyle:ParameterNumber")
class MetadataJsonUTC {

    private static final double DELTA = 1e-6;

    @ParameterizedTest
    @CsvSource({
        "2006:11:19 00:04:34+02:00",
        "2008:03:20 21:14:46.00+02:00",
        "2014:06:24 09:34:01.761+03:00",
        "2012:02:27 23:08:27+3:00",
        "2022:07:07 17:35:12.1Z",
        "2016:10:27 04:50+02:00" })
    void dateTimeParser(String dateTimeString) {
        var dateTime = MetadataJson.dateTimeParser(dateTimeString);
        assertNotNull(dateTime);
    }

    @ParameterizedTest
    @CsvSource({ "2016:10:27 04:50+02:00,2016-10-27T02:50:00Z", "2012:02:27 23:08:27+3:00,2012-02-27T20:08:27Z" })
    void dateTimeParserParsesWithoutSeconds(String input, String expectedUtc) {
        assertEquals(Instant.parse(expectedUtc), MetadataJson.dateTimeParser(input));
    }

    @ParameterizedTest
    @DisplayName("GPS coordinate conversions (parameterized)")
    @CsvSource(value = {
        "60 deg 10' 30.00\"|N|60.17500",
        "60 deg 10' 30.00\"|S|-60.17500",
        "24 deg 58' 00.0\"|E|24.96667",
        "24 deg 58' 00.0\"|W|-24.96667",
        "60 deg 10' 30.00\"|<null>|<null>",
        "60 deg 10' 30.00\"|Z|<null>",
        "   |N|<null>",
        "<null>|N|<null>",
        "invalid|N|<null>" }, nullValues = "<null>", delimiter = '|')
    void gpsConversion(String coordinate, Character cardinal, String expected) {
        var actual = MetadataJson.convertGpsCoordinateStringToBigDecimal(coordinate, cardinal);

        if (expected == null) {
            assertNull(actual);
        } else {
            assertNotNull(actual);
            assertEquals(new BigDecimal(expected), actual);
        }
    }

    @ParameterizedTest
    @DisplayName("GPS coordinate decimal precision")
    @CsvSource(value = {
        // Input DMS | Cardinal | Expected Decimal (5 places)
        "60 deg 10' 30.123\"|N|60.17504",
        "60 deg 10' 30.555\"|N|60.17516",
        "60 deg 10' 30.999\"|N|60.17528",
        "24 deg 58' 0.123\"|E|24.96670",
        "24 deg 58' 0.555\"|E|24.96682",
        "24 deg 58' 0.999\"|E|24.96695" }, delimiter = '|')
    void gpsDecimalPrecision(String dmsCoordinate, Character cardinal, String expected) {
        var result = MetadataJson.convertGpsCoordinateStringToBigDecimal(dmsCoordinate, cardinal);
        assertNotNull(result);
        assertEquals(new BigDecimal(expected), result, "Precision should be exactly 5 decimal places");
        assertEquals(5, result.scale(), "Scale should be 5");
    }

    @ParameterizedTest
    @DisplayName("extractGpsData branch coverage")
    @CsvSource(value = {
        // scenario|Composite.GPSLocation    |GPS.GPSLatitudeRef|GPS.GPSLatitude|Composite.GPSLatitude|GPS.GPSLongitudeRef|GPS
        // .GPSLongitude|Composite.GPSLongitude|expLat|expLatRef|expLon|expLonRef
        "A         |60 deg 10' 30.00\" N, 24 deg 58' 00.0\" E|<null>|<null>            |<null>              |<null>|<null>|<null>|60.17500|N|24.96667|E",
        "B         |60 deg 10' 30.00\" X, 24 deg 58' 00.0\" Y|<null>|<null>            |<null>              |<null>|<null>|<null>|<null>|<null>|<null>|<null>",
        "C         |<null>                                   |N     |60 deg 10' 30.00\"|<null>              |E|24 deg 58' 00.0\"|<null>|60.17500|N|24.96667|E",
        "D         |<null>                                   |<null>|<null>            |60 deg 10' 30.00\" S|<null>|<null>|"
                + "24 deg 58' 00.0\" S|-60.17500|S|-24.96667|S",
        "E         |<null>                                   |<null>|<null>            |<null>              |<null>|<null>|"
                + "<null>|<null>|<null>|<null>|<null>" },
            nullValues = "<null>", delimiter = '|')
    void extractGpsDataParameterized(String scenario, String compositeGpsLocation, String gpsLatRef, String gpsLat, String compositeGpsLat, String gpsLonRef,
            String gpsLon, String compositeGpsLon, String expectedLat, Character expectedLatRef, String expectedLon, Character expectedLonRef) {
        var root = new JSONObject();

        // Build Composite section
        var compositeObj = new JSONObject();
        if (compositeGpsLocation != null) {
            compositeObj.put("GPSLocation", compositeGpsLocation);
        }
        if (compositeGpsLat != null) {
            compositeObj.put("GPSLatitude", compositeGpsLat);
        }
        if (compositeGpsLon != null) {
            compositeObj.put("GPSLongitude", compositeGpsLon);
        }
        if (!compositeObj.isEmpty()) {
            root.put("Composite", compositeObj);
        }

        // Build GPS section
        var gpsObj = new JSONObject();
        if (gpsLatRef != null) {
            gpsObj.put("GPSLatitudeRef", gpsLatRef);
        }
        if (gpsLat != null) {
            gpsObj.put("GPSLatitude", gpsLat);
        }
        if (gpsLonRef != null) {
            gpsObj.put("GPSLongitudeRef", gpsLonRef);
        }
        if (gpsLon != null) {
            gpsObj.put("GPSLongitude", gpsLon);
        }
        if (!gpsObj.isEmpty()) {
            root.put("GPS", gpsObj);
        }

        var entity = MetadataJson.extractGpsData(root);

        if (expectedLat == null) {
            assertNull(entity.getLatitude(), "Scenario " + scenario + " latitude");
            assertNull(entity.getLatitudeRef(), "Scenario " + scenario + " latitudeRef");
        } else {
            assertNotNull(entity.getLatitude(), "Scenario " + scenario);
            assertEquals(new BigDecimal(expectedLat), entity.getLatitude(), "Scenario " + scenario + " latitude");
            assertEquals(expectedLatRef, entity.getLatitudeRef(), "Scenario " + scenario + " latitudeRef");
        }

        if (expectedLon == null) {
            assertNull(entity.getLongitude(), "Scenario " + scenario + " longitude");
            assertNull(entity.getLongitudeRef(), "Scenario " + scenario + " longitudeRef");
        } else {
            assertNotNull(entity.getLongitude(), "Scenario " + scenario);
            assertEquals(new BigDecimal(expectedLon), entity.getLongitude(), "Scenario " + scenario + " longitude");
            assertEquals(expectedLonRef, entity.getLongitudeRef(), "Scenario " + scenario + " longitudeRef");
        }
    }

    @ParameterizedTest
    @DisplayName("Parse audio/video duration from various formats")
    @CsvSource(value = {
        // Format: description, input duration string, expected seconds
        "hours_minutes_seconds, 1:30:45, 5445.0",
        "hours_minutes_seconds_decimals, 1:30:45.5, 5445.5",
        "minutes_seconds, 5:30, 330.0",
        "minutes_seconds_decimals, 5:30.5, 330.5",
        "seconds_only, 42, 42.0",
        "seconds_decimals, 42.5, 42.5",
        "seconds_with_s_qualifier, 24.47 s, 24.47",
        "seconds_with_sec_qualifier, 24.47 sec, 24.47",
        "seconds_with_seconds_qualifier, 24.47 seconds, 24.47",
        "empty_string, , 0.0",
        "null, null, 0.0" }, nullValues = "null")
    void parseDurationString(String description, String durationString, double expectedSeconds) {
        // Create a helper method to test just the string parsing functionality
        double actualSeconds = parseDurationStringHelper(durationString);
        assertEquals(expectedSeconds, actualSeconds, DELTA, "Failed for case: " + description);
    }

    /**
     * Helper method that implements the same parsing logic as in MetadataJson.extractAudioVideoDuration but accepts a string directly instead of requiring a
     * file.
     */
    private double parseDurationStringHelper(String durationStr) {
        // If the string is empty or null, return 0
        if (durationStr == null
            || durationStr.isBlank()) {
            return 0.0;
        }

        // If the string contains colons, it's in a time format (hh:mm:ss or mm:ss)
        if (durationStr.contains(":")) {
            String[] parts = durationStr.split(":");
            double seconds = 0.0;

            if (parts.length == 3) {
                // Format: hh:mm:ss.ms
                seconds += Double.parseDouble(parts[0]) * 3600; // Hours to seconds
                seconds += Double.parseDouble(parts[1]) * 60;   // Minutes to seconds
                seconds += Double.parseDouble(parts[2]);        // Seconds
            } else if (parts.length == 2) {
                // Format: mm:ss.ms
                seconds += Double.parseDouble(parts[0]) * 60;   // Minutes to seconds
                seconds += Double.parseDouble(parts[1]);        // Seconds
            }

            return seconds;
        } else {
            // Check if the string contains a time qualifier like "s", "sec", "seconds"
            String trimmedStr = durationStr.trim();
            if (trimmedStr.endsWith(" s")
                || trimmedStr.endsWith(" sec")
                || trimmedStr.endsWith(" seconds")) {
                // Extract the numeric part before the qualifier
                int spaceIndex = trimmedStr.lastIndexOf(' ');
                if (spaceIndex > 0) {
                    String numericPart = trimmedStr.substring(0, spaceIndex);
                    return Double.parseDouble(numericPart);
                }
            }

            // It's already in seconds format (possibly with decimal)
            return Double.parseDouble(durationStr);
        }
    }

    @ParameterizedTest
    @CsvSource({ "0:04:17,257", "0:02:50 (approx),170", "24.47 s,24.47", "42.5,42.5", "100:00:00,99999", "'',0" })
    void durationFromStringHandlesExifVariants(String input, double expectedSeconds) {
        var duration = MetadataJson.durationFromString(input);
        assertEquals(expectedSeconds, duration.toMillis() / 1000.0, DELTA);
    }

    @ParameterizedTest
    @CsvSource({
        "[{\"AudioBitrate\":\"280 kbps\"}], 0, 280",
        "[{\"AvgBitrate\":\"764 kbps\"}], 0, 764",
        "[{\"NominalBitrate\":\"1.4 Mbps\"}], 0, 1400",
        "[{\"Duration\":\"0:04:17\"}], 32000000, 996" })
    void parseAudioBitRateKbpsUsesFallbacks(String output, long fileSize, int expected) {
        assertEquals(expected, MetadataJson.parseAudioBitRateKbps(output, fileSize));
    }

    @ParameterizedTest
    @CsvSource({
        "ID3,Artist,Motorhead,artist",
        "ID3,Band,Various Artists,album_artist",
        "ID3,Year,2005,year",
        "ID3,Track,03/03,track_total",
        "Vorbis,Album,Uurnapolyjen Paluu,album",
        "Vorbis,Albumartist,Sur-Rur,album_artist",
        "Vorbis,Date,2007,year",
        "Vorbis,Title,Maisema,title",
        "Vorbis,TrackNumber,03,track",
        "Vorbis,Tracktotal,10,track_total",
        "Vorbis,Genre,Punk Rock,genre",
        "ROOT,Artist,Sur-Rur,artist" })
    void musicExtractionHandlesGroupedAndFlatMetadata(String group, String key, String value, String expectedField) {
        var root = new JSONObject();
        if ("ROOT".equals(group)) {
            root.put(key, value);
        } else {
            root.put(group, new JSONObject().put(key, value));
        }

        switch (expectedField) {
            case "artist" -> assertEquals(value, MetadataJson.extractMusicArtist(root));
            case "album_artist" -> assertEquals(value, MetadataJson.extractMusicAlbumArtist(root));
            case "album" -> assertEquals(value, MetadataJson.extractMusicAlbum(root));
            case "year" -> assertEquals(Integer.parseInt(value), MetadataJson.extractMusicYear(root));
            case "title" -> assertEquals(value, MetadataJson.extractMusicTitle(root));
            case "track" -> assertEquals(Integer.parseInt(value), MetadataJson.extractMusicTrackNumber(root));
            case "track_total" -> {
                int expectedTotal = value.contains("/") ? Integer.parseInt(value.split("/")[1]) : Integer.parseInt(value);
                assertEquals(expectedTotal, MetadataJson.extractMusicTrackTotal(root));
            }
            case "genre" -> assertEquals(value, MetadataJson.extractMusicGenre(root));
            default -> throw new IllegalArgumentException("Unexpected field: " + expectedField);
        }
    }

    @ParameterizedTest
    @CsvSource(value = { "03/10,3", "Track 7,7", "'',0", "abc,0" })
    void extractMusicTrackNumberParsesCommonFormats(String input, Integer expected) {
        var root = new JSONObject().put("Vorbis", new JSONObject().put("TrackNumber", input));
        assertEquals(expected, MetadataJson.extractMusicTrackNumber(root));
    }

    @ParameterizedTest
    @CsvSource({ "10,10", "0,0" })
    void extractMusicTrackNumberHandlesNumericJsonValues(int input, int expected) {
        var root = new JSONObject().put("Vorbis", new JSONObject().put("TrackNumber", input));
        assertEquals(expected, MetadataJson.extractMusicTrackNumber(root));
    }

    @ParameterizedTest
    @CsvSource({ "TrackNumber,10,10", "Tracknumber,11,11" })
    void extractMusicTrackNumberSupportsFlatAndVariantKeys(String key, int input, int expected) {
        var root = new JSONObject().put(key, input);
        assertEquals(expected, MetadataJson.extractMusicTrackNumber(root));
    }

    @ParameterizedTest
    @CsvSource(value = { "03/10,10", "Tracktotal 12,12", "'',0", "abc,0" })
    void extractMusicTrackTotalParsesCommonFormats(String input, Integer expected) {
        var root = new JSONObject().put("ID3", new JSONObject().put("Track", input));
        assertEquals(expected, MetadataJson.extractMusicTrackTotal(root));
    }

    @ParameterizedTest
    @CsvSource({ "10,10", "0,0" })
    void extractMusicTrackTotalHandlesNumericJsonValues(int input, int expected) {
        var root = new JSONObject().put("Vorbis", new JSONObject().put("Tracktotal", input));
        assertEquals(expected, MetadataJson.extractMusicTrackTotal(root));
    }

    @ParameterizedTest
    @CsvSource({ "2005,2005", "2005-11-19,2005", "'',0", "unknown,0" })
    void extractMusicYearParsesYearFromCommonFormats(String input, Integer expected) {
        var root = new JSONObject().put("Vorbis", new JSONObject().put("Date", input));
        assertEquals(expected, MetadataJson.extractMusicYear(root));
    }

    @ParameterizedTest
    @CsvSource({ "Band,Various Artists", "Artist,Bombers", "Albumartist,Sur-Rur" })
    void extractMusicAlbumArtistSupportsDifferentTagNames(String key, String expected) {
        var group = "Albumartist".equals(key) ? "Vorbis" : "ID3";
        var root = new JSONObject().put(group, new JSONObject().put(key, expected));
        assertEquals(expected, MetadataJson.extractMusicAlbumArtist(root));
    }

    @ParameterizedTest
    @CsvSource({ "Artist,yes", "Title,yes", "Album,yes", "Genre,yes", "Comment,no" })
    void hasMusicMetadataDetectsGenreAlso(String key, String expectedYesNo) {
        var vorbis = new JSONObject().put(key, "X");
        var root = new JSONObject().put("Vorbis", vorbis);
        if ("yes".equals(expectedYesNo)) {
            assertTrue(MetadataJson.hasMusicMetadata(root));
        } else {
            assertFalse(MetadataJson.hasMusicMetadata(root));
        }
    }

    // ------------------------------------------------------------------
    // hasMusicMetadata — MP3 / ID3-versioned group names
    // ------------------------------------------------------------------

    @ParameterizedTest
    @DisplayName("hasMusicMetadata detects ID3v2.4 (Artist field)")
    @CsvSource({ "Artist,Beherit,yes", "Title,Grave Desecration,yes", "Album,Demonomancy,yes", "Genre,Black Metal,yes", "Comment,Unpublished demo,no" })
    void hasMusicMetadataMp3Id3v24(String field, String value, String expectedYesNo) {
        var id3 = new JSONObject().put(field, value);
        var root = new JSONObject().put("ID3v2_4", id3);
        if ("yes".equals(expectedYesNo)) {
            assertTrue(MetadataJson.hasMusicMetadata(root), "Expected true for field " + field);
        } else {
            assertFalse(MetadataJson.hasMusicMetadata(root), "Expected false for field " + field);
        }
    }

    @ParameterizedTest
    @DisplayName("hasMusicMetadata detects ID3v2.3 (Artist field)")
    @CsvSource({ "Artist,Sepultura,yes", "Title,Roots Bloody Roots,yes", "Album,Roots,yes", "Genre,Thrash Metal,yes", "Comment,Liner notes,no" })
    void hasMusicMetadataMp3Id3v23(String field, String value, String expectedYesNo) {
        var id3 = new JSONObject().put(field, value);
        var root = new JSONObject().put("ID3v2_3", id3);
        if ("yes".equals(expectedYesNo)) {
            assertTrue(MetadataJson.hasMusicMetadata(root), "Expected true for field " + field);
        } else {
            assertFalse(MetadataJson.hasMusicMetadata(root), "Expected false for field " + field);
        }
    }

    @ParameterizedTest
    @DisplayName("hasMusicMetadata detects ID3v1 tags")
    @CsvSource({ "Artist,Metallica,yes", "Title,Enter Sandman,yes", "Album,Metallica,yes", "Genre,Heavy Metal,yes", "Comment,Some note,no" })
    void hasMusicMetadataMp3Id3v1(String field, String value, String expectedYesNo) {
        var id3 = new JSONObject().put(field, value);
        var root = new JSONObject().put("ID3v1", id3);
        if ("yes".equals(expectedYesNo)) {
            assertTrue(MetadataJson.hasMusicMetadata(root), "Expected true for field " + field);
        } else {
            assertFalse(MetadataJson.hasMusicMetadata(root), "Expected false for field " + field);
        }
    }

    @DisplayName("hasMusicMetadata returns false for ID3 group with no recognized music field")
    @org.junit.jupiter.api.Test
    void hasMusicMetadataReturnsFalseForId3GroupWithNoMusicField() {
        // An ID3v2_4 group with only an encoder-settings field (no Artist/Title/Album/Genre)
        var id3 = new JSONObject().put("EncoderSettings", "LAME3.98b");
        var root = new JSONObject().put("ID3v2_4", id3);
        assertFalse(MetadataJson.hasMusicMetadata(root));
    }

    @DisplayName("hasMusicMetadata returns false for audio file with no ID3/Vorbis group")
    @org.junit.jupiter.api.Test
    void hasMusicMetadataReturnsFalseForAudioWithNoMusicGroup() {
        // A pure audio file with only MPEG/File groups (no music metadata)
        var mpeg = new JSONObject().put("AudioBitrate", "192 kbps")
            .put("SampleRate", 44100);
        var file = new JSONObject().put("MIMEType", "audio/mpeg");
        var root = new JSONObject().put("MPEG", mpeg)
            .put("File", file);
        assertFalse(MetadataJson.hasMusicMetadata(root));
    }

    // ------------------------------------------------------------------
    // extractMusicArtist — versioned ID3 group names
    // ------------------------------------------------------------------

    @ParameterizedTest
    @DisplayName("extractMusicArtist reads artist from versioned ID3 groups")
    @CsvSource({ "ID3v2_4,Beherit", "ID3v2_3,Sepultura", "ID3v2_2,Obituary", "ID3v1,Metallica" })
    void extractMusicArtistVersionedId3Groups(String group, String artist) {
        var root = new JSONObject().put(group, new JSONObject().put("Artist", artist));
        assertEquals(artist, MetadataJson.extractMusicArtist(root));
    }

    // ------------------------------------------------------------------
    // extractMusicTitle — versioned ID3 group names
    // ------------------------------------------------------------------

    @ParameterizedTest
    @DisplayName("extractMusicTitle reads title from versioned ID3 groups")
    @CsvSource({ "ID3v2_4,Grave Desecration", "ID3v2_3,Roots Bloody Roots", "ID3v1,Enter Sandman" })
    void extractMusicTitleVersionedId3Groups(String group, String title) {
        var root = new JSONObject().put(group, new JSONObject().put("Title", title));
        assertEquals(title, MetadataJson.extractMusicTitle(root));
    }

    // ------------------------------------------------------------------
    // extractMusicAlbum — versioned ID3 group names
    // ------------------------------------------------------------------

    @ParameterizedTest
    @DisplayName("extractMusicAlbum reads album from versioned ID3 groups")
    @CsvSource({ "ID3v2_4,Demonomancy", "ID3v2_3,Roots", "ID3v1,Metallica" })
    void extractMusicAlbumVersionedId3Groups(String group, String album) {
        var root = new JSONObject().put(group, new JSONObject().put("Album", album));
        assertEquals(album, MetadataJson.extractMusicAlbum(root));
    }

    // ------------------------------------------------------------------
    // extractMusicGenre — versioned ID3 group names
    // ------------------------------------------------------------------

    @ParameterizedTest
    @DisplayName("extractMusicGenre reads genre from versioned ID3 groups")
    @CsvSource({ "ID3v2_4,Black Metal", "ID3v2_3,Thrash Metal", "ID3v1,Heavy Metal" })
    void extractMusicGenreVersionedId3Groups(String group, String genre) {
        var root = new JSONObject().put(group, new JSONObject().put("Genre", genre));
        assertEquals(genre, MetadataJson.extractMusicGenre(root));
    }

    // ------------------------------------------------------------------
    // extractMusicAlbumArtist — Band field in versioned ID3 groups
    // ------------------------------------------------------------------

    @ParameterizedTest
    @DisplayName("extractMusicAlbumArtist reads Band field from versioned ID3 groups")
    @CsvSource({ "ID3v2_4,Beherit", "ID3v2_3,Various Artists" })
    void extractMusicAlbumArtistBandFieldVersionedId3Groups(String group, String band) {
        var root = new JSONObject().put(group, new JSONObject().put("Band", band));
        assertEquals(band, MetadataJson.extractMusicAlbumArtist(root));
    }

    // ------------------------------------------------------------------
    // extractMusicYear — versioned ID3 group names (Year vs RecordingTime)
    // ------------------------------------------------------------------

    @DisplayName("extractMusicYear reads Year (integer) from ID3v2_3 group")
    @org.junit.jupiter.api.Test
    void extractMusicYearFromId3v23IntYear() {
        var root = new JSONObject().put("ID3v2_3", new JSONObject().put("Year", 1990));
        assertEquals(1990, MetadataJson.extractMusicYear(root));
    }

    @DisplayName("extractMusicYear reads RecordingTime from ID3v2_4 group")
    @org.junit.jupiter.api.Test
    void extractMusicYearFromId3v24RecordingTime() {
        var root = new JSONObject().put("ID3v2_4", new JSONObject().put("RecordingTime", 1990));
        assertEquals(1990, MetadataJson.extractMusicYear(root));
    }

    @DisplayName("extractMusicYear reads Year from ID3v1 group")
    @org.junit.jupiter.api.Test
    void extractMusicYearFromId3v1() {
        var root = new JSONObject().put("ID3v1", new JSONObject().put("Year", 1986));
        assertEquals(1986, MetadataJson.extractMusicYear(root));
    }

    // ------------------------------------------------------------------
    // extractMusicTrackNumber — Track field "N/total" format in ID3v2
    // ------------------------------------------------------------------

    @DisplayName("extractMusicTrackNumber reads Track (N/total) from ID3v2_4 group")
    @org.junit.jupiter.api.Test
    void extractMusicTrackNumberFromId3v24TrackWithTotal() {
        var root = new JSONObject().put("ID3v2_4", new JSONObject().put("Track", "04/07"));
        assertEquals(4, MetadataJson.extractMusicTrackNumber(root));
    }

    @DisplayName("extractMusicTrackNumber reads Track from ID3v2_3 group")
    @org.junit.jupiter.api.Test
    void extractMusicTrackNumberFromId3v23() {
        var root = new JSONObject().put("ID3v2_3", new JSONObject().put("Track", "04/07"));
        assertEquals(4, MetadataJson.extractMusicTrackNumber(root));
    }

    // ------------------------------------------------------------------
    // extractMusicTrackTotal — Track field "N/total" format in ID3v2
    // ------------------------------------------------------------------

    @DisplayName("extractMusicTrackTotal reads total from Track (N/total) in ID3v2_4 group")
    @org.junit.jupiter.api.Test
    void extractMusicTrackTotalFromId3v24TrackWithTotal() {
        var root = new JSONObject().put("ID3v2_4", new JSONObject().put("Track", "04/07"));
        assertEquals(7, MetadataJson.extractMusicTrackTotal(root));
    }

    @DisplayName("extractMusicTrackTotal reads total from Track in ID3v2_3 group")
    @org.junit.jupiter.api.Test
    void extractMusicTrackTotalFromId3v23() {
        var root = new JSONObject().put("ID3v2_3", new JSONObject().put("Track", "04/07"));
        assertEquals(7, MetadataJson.extractMusicTrackTotal(root));
    }

    // ------------------------------------------------------------------
    // extractDescription — XMP-dc:Description, then IFD0:ImageDescription, then IPTC:Caption-Abstract (exiftool -g1 groups)

    @Test
    @DisplayName("extractDescription prefers XMP-dc:Description over IFD0:ImageDescription and IPTC:Caption-Abstract")
    void extractDescriptionPrefersXmpDc() {
        var root = new JSONObject().put("XMP-dc", new JSONObject().put("Description", "xmp text"))
            .put("IFD0", new JSONObject().put("ImageDescription", "ifd0 text"))
            .put("IPTC", new JSONObject().put("Caption-Abstract", "iptc text"));
        assertEquals("xmp text", MetadataJson.extractDescription(root));
    }

    @Test
    @DisplayName("extractDescription falls back to IFD0:ImageDescription when XMP-dc:Description is missing or blank")
    void extractDescriptionFallsBackToIfd0() {
        var root = new JSONObject().put("XMP-dc", new JSONObject().put("Description", "  "))
            .put("IFD0", new JSONObject().put("ImageDescription", " ifd0 text "))
            .put("IPTC", new JSONObject().put("Caption-Abstract", "iptc text"));
        assertEquals("ifd0 text", MetadataJson.extractDescription(root));
    }

    @Test
    @DisplayName("extractDescription falls back to IPTC:Caption-Abstract and ignores the family 0 XMP group")
    void extractDescriptionFallsBackToIptc() {
        var root = new JSONObject().put("XMP", new JSONObject().put("Description", "family 0 text"))
            .put("IPTC", new JSONObject().put("Caption-Abstract", "iptc text"));
        assertEquals("iptc text", MetadataJson.extractDescription(root));
    }

    @Test
    @DisplayName("extractDescription returns null without any description and for null input")
    void extractDescriptionReturnsNullWhenAbsent() {
        assertNull(MetadataJson.extractDescription(new JSONObject().put("IFD0", new JSONObject().put("Make", "Canon"))));
        assertNull(MetadataJson.extractDescription(null));
    }

    // extractMusicDescription — Vorbis Description (FLAC) and ID3 Comment (MP3)
    // ------------------------------------------------------------------

    @DisplayName("extractMusicDescription reads Description from Vorbis group (FLAC)")
    @org.junit.jupiter.api.Test
    void extractMusicDescriptionFlacVorbisDescription() {
        var root = new JSONObject().put("Vorbis", new JSONObject().put("Description", "Spookshow 2000 MIX"));
        assertEquals("Spookshow 2000 MIX", MetadataJson.extractMusicDescription(root));
    }

    @DisplayName("extractMusicDescription reads Comment from ID3v2_3 group (MP3)")
    @org.junit.jupiter.api.Test
    void extractMusicDescriptionMp3Id3v23Comment() {
        var root = new JSONObject().put("ID3v2_3", new JSONObject().put("Comment", "Live"));
        assertEquals("Live", MetadataJson.extractMusicDescription(root));
    }

    @DisplayName("extractMusicDescription reads Comment from ID3v2_4 group (MP3)")
    @org.junit.jupiter.api.Test
    void extractMusicDescriptionMp3Id3v24Comment() {
        var root = new JSONObject().put("ID3v2_4", new JSONObject().put("Comment", "Remastered edition"));
        assertEquals("Remastered edition", MetadataJson.extractMusicDescription(root));
    }

    @DisplayName("extractMusicDescription reads Comment from ID3v1 group")
    @org.junit.jupiter.api.Test
    void extractMusicDescriptionId3v1Comment() {
        var root = new JSONObject().put("ID3v1", new JSONObject().put("Comment", "Demo tape"));
        assertEquals("Demo tape", MetadataJson.extractMusicDescription(root));
    }

    @DisplayName("extractMusicDescription returns null when no description/comment field present")
    @org.junit.jupiter.api.Test
    void extractMusicDescriptionReturnsNullWhenAbsent() {
        var root = new JSONObject().put("Vorbis", new JSONObject().put("Title", "Some Track"));
        assertNull(MetadataJson.extractMusicDescription(root));
    }

    @DisplayName("extractMusicDescription returns null for null input")
    @org.junit.jupiter.api.Test
    void extractMusicDescriptionReturnsNullForNullInput() {
        assertNull(MetadataJson.extractMusicDescription(null));
    }

    @ParameterizedTest
    @DisplayName("extractMusicDescription handles multiple ID3 group versions")
    @org.junit.jupiter.params.provider.CsvSource({ "ID3v2_4,Comment,Remastered", "ID3v2_3,Comment,Live", "ID3v2_2,Comment,Demo", "ID3v1,Comment,Demo tape" })
    void extractMusicDescriptionMp3VersionedId3Groups(String group, String field, String value) {
        var root = new JSONObject().put(group, new JSONObject().put(field, value));
        assertEquals(value, MetadataJson.extractMusicDescription(root));
    }

    // ------------------------------------------------------------------
    // Full FLAC metadata JSON — Vorbis Description field
    // ------------------------------------------------------------------

    @DisplayName("extractMusicDescription returns Vorbis Description for full FLAC metadata JSON")
    @org.junit.jupiter.api.Test
    void extractMusicDescriptionFlacFullVorbisSection() {
        // Simulates exiftool -g1 output for the Alice Cooper FLAC file
        var vorbis = new JSONObject().put("Title", "Hands Of Death")
            .put("Artist", "Alice Cooper")
            .put("Albumartist", "Alice Cooper")
            .put("Album", "The Life And Crimes Of Alice Cooper")
            .put("Date", 1999)
            .put("TrackNumber", 79)
            .put("Tracktotal", 81)
            .put("Genre", "Hard Rock")
            .put("Description", "Spookshow 2000 MIX");
        var file = new JSONObject().put("FileType", "FLAC")
            .put("MIMEType", "audio/flac");
        var root = new JSONObject().put("Vorbis", vorbis)
            .put("File", file);

        assertTrue(MetadataJson.hasMusicMetadata(root));
        assertEquals("Spookshow 2000 MIX", MetadataJson.extractMusicDescription(root));
        assertEquals("Hands Of Death", MetadataJson.extractMusicTitle(root));
        assertEquals("Alice Cooper", MetadataJson.extractMusicArtist(root));
    }

    // ------------------------------------------------------------------
    // Full MP3 metadata JSON — ID3v2_3 Comment field (Stormtroopers Of Death)
    // ------------------------------------------------------------------

    @DisplayName("extractMusicDescription returns ID3v2_3 Comment for full MP3 metadata JSON")
    @org.junit.jupiter.api.Test
    void extractMusicDescriptionMp3StormtroopersOfDeathJson() {
        // Simulates the exiftool -g1 output for the SOD MP3 file
        var id3v23 = new JSONObject().put("Title", "Ballad Of Freddy Mercury")
            .put("Artist", "Stormtroopers Of Death")
            .put("Band", "Stormtroopers Of Death")
            .put("Album", "Rise Of The Infidels")
            .put("Year", 2007)
            .put("Track", "17/24")
            .put("Genre", "Trash Metal")
            .put("Comment", "Live");
        var mpeg = new JSONObject().put("AudioBitrate", "128 kbps")
            .put("SampleRate", 44100)
            .put("ChannelMode", "Joint Stereo");
        var file = new JSONObject().put("FileType", "MP3")
            .put("MIMEType", "audio/mpeg");
        var root = new JSONObject().put("ID3v2_3", id3v23)
            .put("MPEG", mpeg)
            .put("File", file);

        assertTrue(MetadataJson.hasMusicMetadata(root));
        assertEquals("Live", MetadataJson.extractMusicDescription(root));
        assertEquals("Ballad Of Freddy Mercury", MetadataJson.extractMusicTitle(root));
        assertEquals("Stormtroopers Of Death", MetadataJson.extractMusicArtist(root));
        assertEquals(2007, MetadataJson.extractMusicYear(root));
        assertEquals(17, MetadataJson.extractMusicTrackNumber(root));
        assertEquals(24, MetadataJson.extractMusicTrackTotal(root));
    }
}
