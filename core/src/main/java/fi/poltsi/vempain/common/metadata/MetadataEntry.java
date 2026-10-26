package fi.poltsi.vempain.common.metadata;

/**
 * One stored metadata value of a file in ExifTool's family 1 terms: the group ({@code IFD0}, {@code ExifIFD}, {@code XMP-dc}, ...), the tag name and the value
 * as text. The file backend maps its metadata rows to these for {@link MetadataJson#collectStandardMetadataAsJson}.
 */
public record MetadataEntry(String group, String key, String value) {}
