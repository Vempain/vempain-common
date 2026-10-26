package fi.poltsi.vempain.common.metadata;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests (UTC) for the parsing branches of {@link MetadataJson} that only need JSON fixtures: resolution and colour depth fall-backs, music tag edge cases,
 * GPS coordinate parsing, durations, dates and the camera metadata collected for exports.
 */
class MetadataJsonParsingUTC {

    private static JSONObject json(String group, JSONObject content) {
        return new JSONObject().put(group, content);
    }

    @Nested
    @DisplayName("image resolution and colour depth")
    class ImageProperties {

        @Test
        void unparsableCompositeSizeFallsBackToTheSubIfdFields() {
            var metadata = json("Composite", new JSONObject().put("ImageSize", "widexhigh")).put("SUBIFD", new JSONObject().put("ImageHeight", 10)
                .put("ImageWidth", 20));

            var resolution = MetadataJson.extractImageResolution(metadata);

            assertThat(resolution.width).isEqualTo(20);
            assertThat(resolution.height).isEqualTo(10);
        }

        @Test
        void sizeWithThreePartsOrOnlyAHeightGivesNoResolution() {
            assertThat(MetadataJson.extractImageResolution(json("Composite", new JSONObject().put("ImageSize", "1x2x3")))).isNull();
            assertThat(MetadataJson.extractImageResolution(json("SUBIFD", new JSONObject().put("ImageHeight", 10)))).isNull();
        }

        @Test
        void colourDepthSumsTripletsAndDefaultsOnEverythingElse() {
            assertThat(MetadataJson.extractImageColorDepth(json("SUBIFD", new JSONObject().put("BitsPerSample", "16, 16, 16")))).isEqualTo(48);
            assertThat(MetadataJson.extractImageColorDepth(json("IFD0", new JSONObject().put("BitsPerSample", 12)))).isEqualTo(12);
            // not a triplet and not a number: the string and the number attempts both fail
            assertThat(MetadataJson.extractImageColorDepth(json("SUBIFD", new JSONObject().put("BitsPerSample", "8 x 8")))).isEqualTo(8);
            // a group that is not an object makes every lookup fail
            assertThat(MetadataJson.extractImageColorDepth(new JSONObject().put("SUBIFD", "oops"))).isEqualTo(8);
            // a decimal is no integer but still a number
            assertThat(MetadataJson.extractImageColorDepth(json("SUBIFD", new JSONObject().put("BitsPerSample", "12.5")))).isEqualTo(12);
            // an overflowing channel is skipped and the others are summed
            assertThat(MetadataJson.extractImageColorDepth(json("SUBIFD", new JSONObject().put("BitsPerSample", "99999999999 8 8")))).isEqualTo(16);
        }
    }

    @Nested
    @DisplayName("music tags")
    class MusicTags {

        @Test
        void absentTagsAreNull() {
            var empty = new JSONObject();
            assertThat(MetadataJson.extractMusicYear(empty)).isNull();
            assertThat(MetadataJson.extractMusicTrackNumber(empty)).isNull();
            assertThat(MetadataJson.extractMusicTrackTotal(empty)).isNull();
            assertThat(MetadataJson.hasMusicMetadata(null)).isFalse();
        }

        @ParameterizedTest
        @CsvSource({ "'',0", "abc,0", "'99 BC',99", "'2020:01:01',2020" })
        void yearFallsBackToAnyNumberOrZero(String value, int expected) {
            assertThat(MetadataJson.extractMusicYear(json("ID3v2_3", new JSONObject().put("Year", value)))).isEqualTo(expected);
        }

        @Test
        void yearFromTheCompositeDate() {
            assertThat(MetadataJson.extractMusicYear(json("Composite", new JSONObject().put("DateTimeOriginal", "2019:05:06 07:08:09")))).isEqualTo(2019);
        }

        @ParameterizedTest
        @CsvSource({ "'',0", "abc,0", "x/5,5", "'7 of 9',7" })
        void trackNumberFallsBackToAnyNumberOrZero(String value, int expected) {
            assertThat(MetadataJson.extractMusicTrackNumber(json("ID3v2_3", new JSONObject().put("Track", value)))).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({ "'',0", "abc,0", "3/abc,3", "7,7", "3/,3", "'2/12 songs',12" })
        void trackTotalFallsBackToAnyNumberOrZero(String value, int expected) {
            assertThat(MetadataJson.extractMusicTrackTotal(json("ID3v2_3", new JSONObject().put("Track", value)))).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("GPS data")
    class GpsDataParsing {

        @Test
        void compositeLocationCarriesBothCoordinatesAndTheOtherFieldsAreCollected() {
            var metadata = json("Composite", new JSONObject().put("GPSLocation", "60 deg 10' 12.00\" N, 24 deg 56' 24.00\" W")
                .put("GPSAltitude", "7 m Above Sea Level")).put("GPS", new JSONObject().put("GPSSatellites", "07")
                    .put("GPSImgDirection", 212.1))
                .put("XMP-iptcCore", new JSONObject().put("Country", "FI")
                    .put("Location", "Mannerheimintie"))
                .put("XMP-photoshop", new JSONObject().put("State", "Uusimaa")
                    .put("City", "Helsinki"));

            var gps = MetadataJson.extractGpsData(metadata);

            assertThat(gps.getLatitudeRef()).isEqualTo('N');
            assertThat(gps.getLatitude()).isEqualByComparingTo(new BigDecimal("60.17000"));
            assertThat(gps.getLongitudeRef()).isEqualTo('W');
            assertThat(gps.getLongitude()).isEqualByComparingTo(new BigDecimal("-24.94000"));
            assertThat(gps.getAltitude()).isEqualTo(7.0);
            assertThat(gps.getSatelliteCount()).isEqualTo(7);
            assertThat(gps.getDirection()).isEqualTo(212.1);
            assertThat(gps.getCountry()).isEqualTo("FI");
            assertThat(gps.getState()).isEqualTo("Uusimaa");
            assertThat(gps.getCity()).isEqualTo("Helsinki");
            assertThat(gps.getStreet()).isEqualTo("Mannerheimintie");
        }

        @Test
        void compositeLocationWithoutCardinalPointsOrWithOddPartsIsIgnored() {
            var noCardinal = MetadataJson.extractGpsData(json("Composite", new JSONObject().put("GPSLocation", "60 deg 10' 12.00\", 24 deg 56' 24.00\"")));
            assertThat(noCardinal.getLatitude()).isNull();
            assertThat(noCardinal.getLongitude()).isNull();

            var blankPart = MetadataJson.extractGpsData(json("Composite", new JSONObject().put("GPSLocation", " , 24 deg 56' 24.00\" E")));
            assertThat(blankPart.getLatitude()).isNull();
            assertThat(blankPart.getLongitudeRef()).isEqualTo('E');

            var threeParts = MetadataJson.extractGpsData(json("Composite", new JSONObject().put("GPSLocation", "1 deg N, 2 deg E, 3 deg")));
            assertThat(threeParts.getLatitude()).isNull();
        }

        @Test
        void unparsableAltitudeAndSatelliteCountAreLoggedAndSkipped() {
            var gps = MetadataJson.extractGpsData(json("GPS", new JSONObject().put("GPSAltitude", "high m")
                .put("GPSSatellites", "many")));
            assertThat(gps.getAltitude()).isNull();
            assertThat(gps.getSatelliteCount()).isNull();
        }

        @Test
        void countryFallsBackToThePhotoshopField() {
            var gps = MetadataJson.extractGpsData(json("XMP-photoshop", new JSONObject().put("Country", "Alankomaat")));
            assertThat(gps.getCountry()).isEqualTo("Alankomaat");
        }

        @Test
        void separateCoordinateTakesTheCardinalPointFromItsOwnSuffix() {
            var gps = MetadataJson.extractGpsData(json("GPS", new JSONObject().put("GPSLatitude", "60 deg 10' 12.00\" S")
                .put("GPSLongitude", "24 deg 56' 24.00\" E")));
            assertThat(gps.getLatitudeRef()).isEqualTo('S');
            assertThat(gps.getLatitude()).isEqualByComparingTo(new BigDecimal("-60.17000"));
            assertThat(gps.getLongitudeRef()).isEqualTo('E');
        }

        @Test
        void unparsableSeparateCoordinateIsDropped() {
            var gps = MetadataJson.extractGpsData(json("GPS", new JSONObject().put("GPSLatitudeRef", "North")
                .put("GPSLatitude", "somewhere up north")
                .put("GPSLongitudeRef", "East")
                .put("GPSLongitude", "24 deg 56' 24.00\"")));
            assertThat(gps.getLatitude()).isNull();
            assertThat(gps.getLatitudeRef()).isNull();
            assertThat(gps.getLongitude()).isEqualByComparingTo(new BigDecimal("24.94000"));
        }

        @Test
        void coordinateConversionHandlesEveryCardinalPointAndBadInput() {
            assertThat(MetadataJson.convertGpsCoordinateStringToBigDecimal("1 deg 30' 0\"", null)).isNull();
            assertThat(MetadataJson.convertGpsCoordinateStringToBigDecimal("1 deg 30' 0\"", 'X')).isNull();
            assertThat(MetadataJson.convertGpsCoordinateStringToBigDecimal("1 deg 30' 0\"", 'S')).isEqualByComparingTo(new BigDecimal("-1.5"));
            assertThat(MetadataJson.convertGpsCoordinateStringToBigDecimal("1 deg 30' 0\"", 'W')).isEqualByComparingTo(new BigDecimal("-1.5"));
            assertThat(MetadataJson.convertGpsCoordinateStringToBigDecimal("1 deg 30' 0\"", 'E')).isEqualByComparingTo(new BigDecimal("1.5"));
            for (var cardinal : List.of('N', 'S', 'E', 'W')) {
                assertThat(MetadataJson.convertGpsCoordinateStringToBigDecimal("", cardinal)).as("blank " + cardinal)
                    .isNull();
                assertThat(MetadataJson.convertGpsCoordinateStringToBigDecimal("60 deg", cardinal)).as("too few parts " + cardinal)
                    .isNull();
                assertThat(MetadataJson.convertGpsCoordinateStringToBigDecimal("1.2.3 4 5", cardinal)).as("bad number " + cardinal)
                    .isNull();
            }
        }
    }

    @Nested
    @DisplayName("JSON helpers")
    class JsonHelpers {

        @Test
        void extractJsonArrayAcceptsArraysScalarsAndIgnoresObjectsAndBlanks() {
            var locations = Map.of("XMP-dc", List.of("Subject"));
            assertThat(MetadataJson.extractJsonArray(json("XMP-dc", new JSONObject().put("Subject", new JSONArray().put("a")
                .put(2))), locations)).containsExactly("a", "2");
            assertThat(MetadataJson.extractJsonArray(json("XMP-dc", new JSONObject().put("Subject", " single ")), locations)).containsExactly("single");
            assertThat(MetadataJson.extractJsonArray(json("XMP-dc", new JSONObject().put("Subject", "  ")), locations)).isEmpty();
            assertThat(MetadataJson.extractJsonArray(json("XMP-dc", new JSONObject().put("Subject", new JSONObject().put("x", 1))), locations)).isEmpty();
            assertThat(MetadataJson.extractJsonArray(new JSONObject(), locations)).isEmpty();
        }

        @Test
        void numbersAreReadFromStringsFlatKeysAndRejectedWhenUnparsable() {
            assertThat(MetadataJson.extractOriginalSecondFraction(json("ExifIFD", new JSONObject().put("SubSecTimeOriginal", "12")))).isEqualTo(12);
            assertThat(MetadataJson.extractOriginalSecondFraction(json("ExifIFD", new JSONObject().put("SubSecTimeOriginal", "abc")))).isZero();
            // padded text is no JSON number but still parses as a double
            assertThat(MetadataJson.extractOriginalSecondFraction(json("ExifIFD", new JSONObject().put("SubSecTimeOriginal", " 12 ")))).isEqualTo(12);
            assertThat(MetadataJson.extractOriginalSecondFraction(new JSONObject().put("SubSecTimeOriginal", 5))).isEqualTo(5);
            assertThat(MetadataJson.extractOriginalSecondFraction(new JSONObject().put("SubSecTimeOriginal", "7.5"))).isEqualTo(7);
            assertThat(MetadataJson.extractOriginalSecondFraction(new JSONObject().put("SubSecTimeOriginal", "x"))).isZero();
        }

        @Test
        void firstValueSearchesEveryGroupCaseInsensitivelyAndJoinsArrays() {
            var grouped = new JSONObject().put("File", new JSONObject().put("FileType", "PDF"))
                .put("PDF", new JSONObject().put("PageCount", 3))
                .put("XMP-dc", new JSONObject().put("Subject", new JSONArray().put("a")
                    .put("b")));
            assertThat(MetadataJson.firstValue(grouped, "pagecount")).isEqualTo("3");
            assertThat(MetadataJson.firstValue(grouped, "Missing", "FileType")).isEqualTo("PDF");
            assertThat(MetadataJson.firstValue(grouped, "Subject")).isEqualTo("a, b");
            assertThat(MetadataJson.firstValue(grouped, "Nope")).isNull();
            assertThat(MetadataJson.firstValue(null, "FileType")).isNull();
            // flat -j output keeps the tags on the root level
            assertThat(MetadataJson.firstValue(new JSONObject().put("Duration", "0:01:00"), "Duration")).isEqualTo("0:01:00");
        }

        @Test
        void perTypeFieldsComeFromTheGroupedJson() {
            var media = new JSONObject().put("QuickTime", new JSONObject().put("VideoFrameRate", 12)
                .put("Duration", "0:00:02")
                .put("AudioSampleRate", 8000)
                .put("AudioChannels", 1)
                .put("AudioFormat", "mp4a"))
                .put("Composite", new JSONObject().put("AvgBitrate", "2.5 Mbps"));
            assertThat(MetadataJson.extractFrameRate(media)).isEqualTo(12.0);
            assertThat(MetadataJson.extractAudioVideoDuration(media)).isEqualTo(Duration.ofSeconds(2));
            assertThat(MetadataJson.extractAudioSampleRate(media)).isEqualTo(8000);
            assertThat(MetadataJson.extractAudioChannels(media)).isEqualTo(1);
            assertThat(MetadataJson.extractAudioBitRate(media, 0)).isEqualTo(2500);
            assertThat(MetadataJson.extractAudioCodec(media)).isEmpty();

            var document = new JSONObject().put("File", new JSONObject().put("FileType", "PDF")
                .put("ImageWidth", "64")
                .put("ImageHeight", 32))
                .put("PDF", new JSONObject().put("PageCount", "1"));
            assertThat(MetadataJson.extractDocumentPageCount(document)).isEqualTo(1);
            assertThat(MetadataJson.extractDocumentFormat(document)).isEqualTo("PDF");
            assertThat(MetadataJson.extractXYResolution(document)).isEqualTo(new java.awt.Dimension(64, 32));
            assertThat(MetadataJson.extractVectorLayersCount(document)).isZero();
            assertThat(MetadataJson.extractIconIsScalable(document)).isFalse();

            var archive = new JSONObject().put("ZIP", new JSONObject().put("ZipCompression", "Deflated")
                .put("ZipUncompressedSize", 2048)
                .put("Encrypted", "Yes"))
                .put("Font", new JSONObject().put("FontFamily", "Inter")
                    .put("FontWeight", "Bold")
                    .put("FontStyle", "Italic")
                    .put("Scalable", true));
            assertThat(MetadataJson.extractArchiveCompressionMethod(archive)).isEqualTo("Deflated");
            assertThat(MetadataJson.extractArchiveUncompressedSize(archive)).isEqualTo(2048);
            assertThat(MetadataJson.extractArchiveContentCount(archive)).isZero();
            assertThat(MetadataJson.extractArchiveIsEncrypted(archive)).isTrue();
            assertThat(MetadataJson.extractFontFamily(archive)).isEqualTo("Inter");
            assertThat(MetadataJson.extractFontWeight(archive)).isEqualTo("Bold");
            assertThat(MetadataJson.extractFontStyle(archive)).isEqualTo("Italic");
            assertThat(MetadataJson.extractIconIsScalable(archive)).isTrue();

            // Nothing read at all: every field has its empty value
            assertThat(MetadataJson.extractFrameRate(null)).isZero();
            assertThat(MetadataJson.extractXYResolution(new JSONObject())).isNull();
            assertThat(MetadataJson.extractArchiveUncompressedSize(new JSONObject().put("ZIP", new JSONObject().put("ZipUncompressedSize", "2 kB"))))
                .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("durations, bit rates and dates")
    class DurationsAndDates {

        @ParameterizedTest
        @CsvSource({ "1:2:3:4,0", "a:b,0", "'-5 s',0", "1:30,90", "'-1:30',0", "'999999 s',99999" })
        void durationStringsAreParsedClampedOrZero(String value, long expectedSeconds) {
            assertThat(MetadataJson.durationFromString(value)).isEqualTo(Duration.ofSeconds(expectedSeconds));
        }

        @Test
        void bitRateUsesMegabitUnitsAndNeedsADurationAndSize() {
            assertThat(MetadataJson.parseAudioBitRateKbps("[{\"AvgBitrate\":\"2.5 Mbps\"}]", 1000)).isEqualTo(2500);
            assertThat(MetadataJson.parseAudioBitRateKbps("[{\"Duration\":\"0 s\"}]", 1000)).isZero();
            assertThat(MetadataJson.parseAudioBitRateKbps("[{\"Duration\":\"10 s\"}]", 0)).isZero();
            assertThat(MetadataJson.parseAudioBitRateKbps("not json", 1000)).isZero();
        }

        @Test
        void yearOnlyAndYearDayMonthDatesAreParsed() {
            var zone = ZoneId.systemDefault();
            assertThat(MetadataJson.dateTimeParser("1991")).isEqualTo(LocalDate.of(1991, 1, 1)
                .atStartOfDay(zone)
                .toInstant());
            assertThat(MetadataJson.dateTimeParser("2005:28:07")).isEqualTo(LocalDate.of(2005, 7, 28)
                .atStartOfDay(zone)
                .toInstant());
            assertThat(MetadataJson.dateTimeParser("2005:99:07")).isNull();
            assertThat(MetadataJson.dateTimeParser("garbage")).isNull();
            assertThat(MetadataJson.dateTimeParser("2024:01:02 03:04:05+2:00")).isEqualTo(MetadataJson.dateTimeParser("2024:01:02 03:04:05+02:00"));
        }
    }

    @Nested
    @DisplayName("collectStandardMetadataAsJson")
    class StandardMetadata {

        private MetadataEntry row(String group, String key, String value) {
            return new MetadataEntry(group, key, value);
        }

        @Test
        void keepsUnparsableNumbersAsTextAndFillsRightsFromTheFile() throws Exception {
            var file = new RightsAndCreator("Entity Artist", "e@x", "FI", "http://creator", "Entity Rights", " terms ", "http://rights");
            var rows = List.of(row("IFD0", "Make", "Canon"), row("IFD0", "Make", "Duplicate"), row(null, "Model", "EOS"), row("ExifIFD", "FNumber", "1/6"),
                    row("Composite", "ISO", "auto"), row("File", "BitsPerSample", "x"), row("Composite", "Megapixels", "many"),
                    row("Composite", "FOV", "60 deg"), row("Composite", "CircleOfConfusion", "0.03 mm"), row("File", "ImageWidth", "640"),
                    row("File", "ImageHeight", "480"));

            var json = MetadataJson.collectStandardMetadataAsJson(rows, file);
            var node = JsonMapper.shared()
                .readTree(json)
                .get(0);

            assertThat(node.get("Make")
                .asString()).isEqualTo("Canon");
            assertThat(node.get("Artist")
                .asString()).isEqualTo("Entity Artist");
            assertThat(node.get("Copyright")
                .asString()).isEqualTo("Entity Rights");
            assertThat(node.get("CopyrightFlag")
                .asBoolean()).isTrue();
            assertThat(node.get("Rights")
                .asString()).isEqualTo("Entity Rights");
            assertThat(node.get("FNumber")
                .asString()).isEqualTo("1/6");
            assertThat(node.get("ISO")
                .asString()).isEqualTo("auto");
            assertThat(node.get("BitsPerSample")
                .asString()).isEqualTo("x");
            assertThat(node.get("Megapixels")
                .asString()).isEqualTo("many");
            assertThat(node.get("ImageSize")
                .asString()).isEqualTo("640x480");
            assertThat(node.get("FOV")
                .asString()).isEqualTo("60 deg");
            assertThat(node.get("WebStatement")
                .asString()).isEqualTo("http://rights");
            assertThat(node.get("UsageTerms")
                .asString()).isEqualTo("terms");
            assertThat(node.get("CreatorWorkEmail")
                .asString()).isEqualTo("e@x");
            assertThat(node.get("CreatorCountry")
                .asString()).isEqualTo("FI");
            assertThat(node.get("CreatorWorkURL")
                .asString()).isEqualTo("http://creator");
            assertThat(node.has("Model")).isFalse();
        }

        @Test
        void numericValuesAreTypedAndBadDecimalsStayText() throws Exception {
            var rows = List.of(row("ExifIFD", "FNumber", "1.2.3"), row("Composite", "ISO", "99999999999"), row("Composite", "Megapixels", "12.3 MP"),
                    row("File", "BitsPerSample", "8 bits"));

            var node = JsonMapper.shared()
                .readTree(MetadataJson.collectStandardMetadataAsJson(rows, null))
                .get(0);

            assertThat(node.get("FNumber")
                .asString()).isEqualTo("1.2.3");
            assertThat(node.get("ISO")
                .asString()).isEqualTo("99999999999");
            assertThat(node.get("Megapixels")
                .asDouble()).isEqualTo(12.3);
            assertThat(node.get("BitsPerSample")
                .asInt()).isEqualTo(8);
        }

        @Test
        void blankFallbackValuesProduceAnEmptyObject() {
            var blank = new RightsAndCreator(" ", null, "", null, " ", null, "");

            assertThat(MetadataJson.collectStandardMetadataAsJson(List.of(), blank)).isEqualTo("[{}]");
            assertThat(MetadataJson.collectStandardMetadataAsJson(null, null)).isEqualTo("[{}]");
        }
    }
}
