package fi.poltsi.vempain.common.metadata;

/**
 * Rights holder and creator contact values a backend stores for a file; {@link MetadataJson#collectStandardMetadataAsJson} writes them into an export
 * derivative when the file's own metadata lacks them. Any field may be {@code null}.
 */
public record RightsAndCreator(String creatorName, String creatorEmail, String creatorCountry, String creatorUrl, String rightsHolder, String rightsTerms,
        String rightsUrl) {}
