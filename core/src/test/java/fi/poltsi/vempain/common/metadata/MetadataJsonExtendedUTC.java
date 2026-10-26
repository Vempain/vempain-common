package fi.poltsi.vempain.common.metadata;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Extended unit tests (UTC) for {@link MetadataJson} JSON-based extraction methods.
 */
class MetadataJsonExtendedUTC {

    /** The metadata rows of a typical camera file, as the file backend stores them. */
    static List<MetadataEntry> generateMetadataEntities() {
        return List.of(new MetadataEntry("IFD0", "Artist", "John Smith"), new MetadataEntry("ExifIFD", "FNumber", "2.8"),
                new MetadataEntry("ExifIFD", "ExposureTime", "1/200"), new MetadataEntry("IFD0", "Make", "Canon"), new MetadataEntry("ExifIFD", "ISO", "400"),
                new MetadataEntry("File", "BitsPerSample", "8"), new MetadataEntry("Composite", "Megapixels", "24.2"),
                new MetadataEntry("File", "ImageWidth", "1920"), new MetadataEntry("File", "ImageHeight", "1080"));
    }

    // ------------------------------------------------------------------
    // extractDescription
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractDescription")
    class ExtractDescription {

        @Test
        void returnsXmpDcDescription() {
            // exiftool -g1 names the group "XMP-dc"; the family 0 "XMP" group never appears in the scanner's output
            var json = new JSONObject();
            var xmp = new JSONObject();
            xmp.put("Description", "A nice photo");
            json.put("XMP-dc", xmp);
            assertThat(MetadataJson.extractDescription(json)).isEqualTo("A nice photo");
        }

        @Test
        void returnsIfd0ImageDescription() {
            var json = new JSONObject();
            json.put("IFD0", new JSONObject().put("ImageDescription", "Camera caption"));
            assertThat(MetadataJson.extractDescription(json)).isEqualTo("Camera caption");
        }

        @Test
        void fallsBackToIptcCaption() {
            var json = new JSONObject();
            var iptc = new JSONObject();
            iptc.put("Caption-Abstract", "IPTC caption");
            json.put("IPTC", iptc);
            assertThat(MetadataJson.extractDescription(json)).isEqualTo("IPTC caption");
        }

        @Test
        void returnsNullWhenNoDescription() {
            var json = new JSONObject();
            assertNull(MetadataJson.extractDescription(json));
        }
    }

    // ------------------------------------------------------------------
    // extractMimetype
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractMimetype")
    class ExtractMimetype {

        @Test
        void returnsFileMimetype() {
            var json = new JSONObject();
            var file = new JSONObject();
            file.put("MIMEType", "image/jpeg");
            json.put("File", file);
            assertThat(MetadataJson.extractMimetype(json)).isEqualTo("image/jpeg");
        }

        @Test
        void returnsXmpMimetype() {
            var json = new JSONObject();
            var xmp = new JSONObject();
            xmp.put("MIMEType", "application/pdf");
            json.put("XMP", xmp);
            assertThat(MetadataJson.extractMimetype(json)).isEqualTo("application/pdf");
        }

        @Test
        void returnsNullWhenNoMimetype() {
            assertNull(MetadataJson.extractMimetype(new JSONObject()));
        }
    }

    // ------------------------------------------------------------------
    // extractOriginalDateTime
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractOriginalDateTime")
    class ExtractOriginalDateTime {

        @Test
        void returnsExifIFDDateTime() {
            var json = new JSONObject();
            var exifIFD = new JSONObject();
            exifIFD.put("DateTimeOriginal", "2023:06:15 12:34:56");
            json.put("ExifIFD", exifIFD);
            assertThat(MetadataJson.extractOriginalDateTime(json)).isEqualTo("2023:06:15 12:34:56");
        }

        @Test
        void returnsCompositeDateTime() {
            var json = new JSONObject();
            var composite = new JSONObject();
            composite.put("DateTimeOriginal", "2022:01:01 00:00:00");
            json.put("Composite", composite);
            assertThat(MetadataJson.extractOriginalDateTime(json)).isEqualTo("2022:01:01 00:00:00");
        }

        @Test
        void returnsNullWhenNoDateTime() {
            assertNull(MetadataJson.extractOriginalDateTime(new JSONObject()));
        }
    }

    // ------------------------------------------------------------------
    // extractOriginalSecondFraction
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractOriginalSecondFraction")
    class ExtractOriginalSecondFraction {

        @Test
        void returnsSubSecValue() {
            var json = new JSONObject();
            var exifIFD = new JSONObject();
            exifIFD.put("SubSecTimeOriginal", 76);
            json.put("ExifIFD", exifIFD);
            assertThat(MetadataJson.extractOriginalSecondFraction(json)).isEqualTo(76);
        }

        @Test
        void returnsZeroWhenNotPresent() {
            assertThat(MetadataJson.extractOriginalSecondFraction(new JSONObject())).isEqualTo(0);
        }
    }

    // ------------------------------------------------------------------
    // extractOriginalDocumentId
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractOriginalDocumentId")
    class ExtractOriginalDocumentId {

        @Test
        void returnsDocumentId() {
            var json = new JSONObject();
            var xmpMM = new JSONObject();
            xmpMM.put("OriginalDocumentID", "xmp.did:ABC123");
            json.put("XMP-xmpMM", xmpMM);
            assertThat(MetadataJson.extractOriginalDocumentId(json)).isEqualTo("xmp.did:ABC123");
        }

        @Test
        void fallsBackToDocumentID() {
            var json = new JSONObject();
            var xmpMM = new JSONObject();
            xmpMM.put("DocumentID", "xmp.did:DEF456");
            json.put("XMP-xmpMM", xmpMM);
            assertThat(MetadataJson.extractOriginalDocumentId(json)).isEqualTo("xmp.did:DEF456");
        }

        @Test
        void returnsNullWhenAbsent() {
            assertNull(MetadataJson.extractOriginalDocumentId(new JSONObject()));
        }
    }

    // ------------------------------------------------------------------
    // extractSubjects
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractSubjects")
    class ExtractSubjects {

        @Test
        void returnsSubjectArray() {
            var json = new JSONObject();
            var xmp = new JSONObject();
            xmp.put("Subject", new org.json.JSONArray(List.of("nature", "landscape")));
            json.put("XMP", xmp);
            var subjects = MetadataJson.extractSubjects(json);
            assertThat(subjects).containsExactlyInAnyOrder("nature", "landscape");
        }

        @Test
        void returnsSingleSubjectAsString() {
            var json = new JSONObject();
            var xmp = new JSONObject();
            xmp.put("Subject", "singleKeyword");
            json.put("XMP", xmp);
            var subjects = MetadataJson.extractSubjects(json);
            assertThat(subjects).containsExactly("singleKeyword");
        }

        @Test
        void returnsEmptyWhenAbsent() {
            assertThat(MetadataJson.extractSubjects(new JSONObject())).isEmpty();
        }

        @Test
        void deduplicates() {
            var json = new JSONObject();
            var xmp = new JSONObject();
            xmp.put("Subject", new org.json.JSONArray(List.of("tag", "tag", "unique")));
            json.put("XMP", xmp);
            var subjects = MetadataJson.extractSubjects(json);
            assertThat(subjects).hasSize(2)
                .contains("tag", "unique");
        }

        @Test
        void combinesSubjectsFromAllMetadataLocations() {
            var json = new JSONObject();
            json.put("XMP-dc", new JSONObject().put("Subject", List.of("xmp", "shared")));
            json.put("XMP-lr", new JSONObject().put("HierarchicalSubject", List.of("hierarchical"))
                .put("WeightedFlatSubject", List.of("weighted", "shared")));
            json.put("IPTC", new JSONObject().put("Keywords", List.of("iptc")));

            assertThat(MetadataJson.extractSubjects(json)).containsExactly("xmp", "shared", "hierarchical", "weighted", "iptc");
        }
    }

    // ------------------------------------------------------------------
    // extractRightsHolder / extractRightsTerms / extractRightsUrl
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Rights extraction")
    class RightsExtraction {

        @Test
        void extractRightsHolderFromDcRights() {
            var json = new JSONObject();
            var dcXmp = new JSONObject();
            dcXmp.put("Rights", "© 2024 Test Author");
            json.put("XMP-dc", dcXmp);
            assertThat(MetadataJson.extractRightsHolder(json)).isEqualTo("© 2024 Test Author");
        }

        @Test
        void extractRightsHolderReturnsNullWhenAbsent() {
            assertNull(MetadataJson.extractRightsHolder(new JSONObject()));
        }

        @Test
        void extractRightsTermsFromUsageTerms() {
            var json = new JSONObject();
            var rights = new JSONObject();
            rights.put("UsageTerms", "All rights reserved");
            json.put("XMP-xmpRights", rights);
            assertThat(MetadataJson.extractRightsTerms(json)).isEqualTo("All rights reserved");
        }

        @Test
        void extractRightsTermsReturnsNullWhenAbsent() {
            assertNull(MetadataJson.extractRightsTerms(new JSONObject()));
        }

        @Test
        void extractRightsUrlFromWebStatement() {
            var json = new JSONObject();
            var rights = new JSONObject();
            rights.put("WebStatement", "https://example.com/license");
            json.put("XMP-xmpRights", rights);
            assertThat(MetadataJson.extractRightsUrl(json)).isEqualTo("https://example.com/license");
        }

        @Test
        void extractRightsUrlReturnsNullWhenAbsent() {
            assertNull(MetadataJson.extractRightsUrl(new JSONObject()));
        }
    }

    // ------------------------------------------------------------------
    // extractCreator* methods
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Creator extraction")
    class CreatorExtraction {

        @Test
        void extractCreatorNameFromIFD0Artist() {
            var json = new JSONObject();
            var ifd0 = new JSONObject();
            ifd0.put("Artist", "Jane Doe");
            json.put("IFD0", ifd0);
            assertThat(MetadataJson.extractCreatorName(json)).isEqualTo("Jane Doe");
        }

        @Test
        void extractCreatorNameReturnsNullWhenAbsent() {
            assertNull(MetadataJson.extractCreatorName(new JSONObject()));
        }

        @Test
        void extractCreatorEmailFromIptcCore() {
            var json = new JSONObject();
            var iptcCore = new JSONObject();
            iptcCore.put("CreatorWorkEmail", "jane@example.com");
            json.put("XMP-iptcCore", iptcCore);
            assertThat(MetadataJson.extractCreatorEmail(json)).isEqualTo("jane@example.com");
        }

        @Test
        void extractCreatorEmailReturnsNullWhenAbsent() {
            assertNull(MetadataJson.extractCreatorEmail(new JSONObject()));
        }

        @Test
        void extractCreatorCountryFromIptcCore() {
            var json = new JSONObject();
            var iptcCore = new JSONObject();
            iptcCore.put("CreatorCountry", "FI");
            json.put("XMP-iptcCore", iptcCore);
            assertThat(MetadataJson.extractCreatorCountry(json)).isEqualTo("FI");
        }

        @Test
        void extractCreatorCountryReturnsNullWhenAbsent() {
            assertNull(MetadataJson.extractCreatorCountry(new JSONObject()));
        }

        @Test
        void extractCreatorUrlFromIptcCore() {
            var json = new JSONObject();
            var iptcCore = new JSONObject();
            iptcCore.put("CreatorWorkURL", "https://example.com");
            json.put("XMP-iptcCore", iptcCore);
            assertThat(MetadataJson.extractCreatorUrl(json)).isEqualTo("https://example.com");
        }

        @Test
        void extractCreatorUrlReturnsNullWhenAbsent() {
            assertNull(MetadataJson.extractCreatorUrl(new JSONObject()));
        }
    }

    // ------------------------------------------------------------------
    // extractLabel
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractLabel")
    class ExtractLabel {

        @Test
        void returnsLabelFromXmp() {
            var json = new JSONObject();
            var xmpXmp = new JSONObject();
            xmpXmp.put("Label", "Red");
            json.put("XMP-xmp", xmpXmp);
            assertThat(MetadataJson.extractLabel(json)).isEqualTo("Red");
        }

        @Test
        void returnsNullWhenAbsent() {
            assertNull(MetadataJson.extractLabel(new JSONObject()));
        }
    }

    // ------------------------------------------------------------------
    // extractGpsTime
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractGpsTime")
    class ExtractGpsTime {

        @Test
        void returnsGpsdatetimeFromComposite() {
            var json = new JSONObject();
            var composite = new JSONObject();
            composite.put("GPSDateTime", "2023:06:15 10:20:30Z");
            json.put("Composite", composite);
            var result = MetadataJson.extractGpsTime(json);
            assertNotNull(result);
        }

        @Test
        void returnsNullFromMalformedDateTime() {
            var json = new JSONObject();
            var composite = new JSONObject();
            composite.put("GPSDateTime", "not-a-date");
            json.put("Composite", composite);
            assertNull(MetadataJson.extractGpsTime(json));
        }

        @Test
        void combinesDateAndTimeStamp() {
            var json = new JSONObject();
            var gps = new JSONObject();
            gps.put("GPSTimeStamp", "10:20:30");
            gps.put("GPSDateStamp", "2023:06:15");
            json.put("GPS", gps);
            var result = MetadataJson.extractGpsTime(json);
            assertNotNull(result);
        }

        @Test
        void returnsNullWhenAbsent() {
            assertNull(MetadataJson.extractGpsTime(new JSONObject()));
        }
    }

    // ------------------------------------------------------------------
    // extractImageResolution
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractImageResolution")
    class ExtractImageResolution {

        @Test
        void returnsResolutionFromSubIFD() {
            var json = new JSONObject();
            var subIFD = new JSONObject();
            subIFD.put("ImageWidth", 1920);
            subIFD.put("ImageHeight", 1080);
            json.put("SUBIFD", subIFD);
            var dim = MetadataJson.extractImageResolution(json);
            assertNotNull(dim);
            assertThat(dim.width).isEqualTo(1920);
            assertThat(dim.height).isEqualTo(1080);
        }

        @Test
        void returnsResolutionFromComposite() {
            // Composite section uses "ImageSize" field containing "WIDTHxHEIGHT" string
            var json = new JSONObject();
            var composite = new JSONObject();
            composite.put("ImageSize", "800x600");
            json.put("Composite", composite);
            var dim = MetadataJson.extractImageResolution(json);
            assertNotNull(dim);
            assertThat(dim.width).isEqualTo(800);
            assertThat(dim.height).isEqualTo(600);
        }

        @Test
        void returnsNullWhenAbsent() {
            assertNull(MetadataJson.extractImageResolution(new JSONObject()));
        }
    }

    // ------------------------------------------------------------------
    // extractImageColorDepth
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractImageColorDepth")
    class ExtractImageColorDepth {

        @Test
        void returnsColorDepthFromSUBIFD() {
            var json = new JSONObject();
            var subIFD = new JSONObject();
            subIFD.put("BitsPerSample", 8);
            json.put("SUBIFD", subIFD);
            assertThat(MetadataJson.extractImageColorDepth(json)).isEqualTo(8);
        }

        @Test
        void returnsColorDepthFromIFD0() {
            // extractImageColorDepth searches SUBIFD and IFD0, not a "File" section
            var json = new JSONObject();
            var ifd0 = new JSONObject();
            ifd0.put("BitsPerSample", 24);
            json.put("IFD0", ifd0);
            assertThat(MetadataJson.extractImageColorDepth(json)).isEqualTo(24);
        }

        @Test
        void returnsDefaultEightWhenAbsent() {
            // Default when no BitsPerSample metadata is found is 8
            assertThat(MetadataJson.extractImageColorDepth(new JSONObject())).isEqualTo(8);
        }

        @Test
        void handlesStringValue() {
            var json = new JSONObject();
            var subIFD = new JSONObject();
            subIFD.put("BitsPerSample", "16");
            json.put("SUBIFD", subIFD);
            // Should parse string "16" as integer
            assertThat(MetadataJson.extractImageColorDepth(json)).isEqualTo(16);
        }
    }

    // ------------------------------------------------------------------
    // extractImageDpi
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("extractImageDpi")
    class ExtractImageDpi {

        @Test
        void returnsDpiFromSubIFD() {
            // extractImageDpi only looks in SUBIFD, not IFD0
            var json = new JSONObject();
            var subIFD = new JSONObject();
            subIFD.put("XResolution", 300);
            json.put("SUBIFD", subIFD);
            assertThat(MetadataJson.extractImageDpi(json)).isEqualTo(300);
        }

        @Test
        void returnsDpiFromSubIFD72() {
            var json = new JSONObject();
            var subIFD = new JSONObject();
            subIFD.put("XResolution", 72);
            json.put("SUBIFD", subIFD);
            assertThat(MetadataJson.extractImageDpi(json)).isEqualTo(72);
        }

        @Test
        void returnsDefaultSeventyTwoWhenAbsent() {
            // Default when no XResolution is found in SUBIFD is 72
            assertThat(MetadataJson.extractImageDpi(new JSONObject())).isEqualTo(72);
        }
    }

    // ------------------------------------------------------------------
    // collectStandardMetadataAsJson
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("collectStandardMetadataAsJson")
    class CollectStandardMetadataAsJson {

        @Test
        void emptyMetadataListReturnsJsonArray() {
            var result = MetadataJson.collectStandardMetadataAsJson(List.of(), null);
            assertNotNull(result);
            assertThat(result).startsWith("[");
        }

        @Test
        void nullMetadataListReturnsJsonArray() {
            var result = MetadataJson.collectStandardMetadataAsJson(null, null);
            assertNotNull(result);
            assertThat(result).startsWith("[");
        }

        @Test
        void withRightsAndCreatorIncludesThemInOutput() {
            var mockFile = new RightsAndCreator("Test Creator", "creator@example.com", "FI", "https://creator.example.com", "© 2024", "All rights reserved",
                    "https://license.example.com");

            var result = MetadataJson.collectStandardMetadataAsJson(List.of(), mockFile);
            assertThat(result).contains("Test Creator");
            assertThat(result).contains("© 2024");
        }

        @Test
        void withMetadataEntriesExtractsFields() {
            var mockFile = new RightsAndCreator(null, null, null, null, null, null, null);

            var result = MetadataJson.collectStandardMetadataAsJson(generateMetadataEntities(), mockFile);
            assertThat(result).contains("John Smith");
            assertThat(result).contains("Canon");
            assertThat(result).contains("400");
            assertThat(result).contains("8");
            assertThat(result).contains("1920x1080");
        }
    }

    // ------------------------------------------------------------------
    // metadataToJsonObject
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("metadataToJsonObject")
    class MetadataToJsonObject {

        @Test
        void parsesValidJson() {
            var json = "[{\"File\":{\"MIMEType\":\"image/jpeg\"}}]";
            var result = MetadataJson.metadataToJsonObject(json);
            assertNotNull(result);
        }

        @Test
        void throwsForNullInput() {
            // metadataToJsonObject passes null directly to new JSONArray() which throws NPE
            assertThrows(NullPointerException.class, () -> MetadataJson.metadataToJsonObject(null));
        }

        @Test
        void throwsForBlankInput() {
            // blank string is not valid JSON array → JSONException
            assertThrows(Exception.class, () -> MetadataJson.metadataToJsonObject("   "));
        }

        @Test
        void throwsForInvalidJson() {
            assertThrows(Exception.class, () -> MetadataJson.metadataToJsonObject("not-json"));
        }

        @Test
        void returnsNullForEmptyArray() {
            assertNull(MetadataJson.metadataToJsonObject("[]"));
        }
    }
}
