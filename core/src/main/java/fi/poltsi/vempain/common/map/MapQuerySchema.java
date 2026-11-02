package fi.poltsi.vempain.common.map;

import lombok.Builder;

/**
 * Where a hosting backend keeps its GPS-tagged resources, so that {@link MapClusterQuery} can build the clustering SQL for it. Every value is a plain SQL
 * identifier or fragment the backend writes itself, never request text. The file table must carry an {@code acl_id} column checked against the shared
 * {@code acl} table, the location table the five-decimal {@code latitude}/{@code longitude} columns with their {@code latitude_ref}/{@code longitude_ref}
 * hemisphere characters.
 *
 * @param fileTable         the table of the resources, for example {@code files} or {@code site_file}
 * @param idColumn          the id column of the file table
 * @param aclIdColumn       the ACL id column of the file table
 * @param filenameColumn    the file name column
 * @param filePathColumn    the directory path column
 * @param fileTypeColumn    the file type column, holding {@code FileTypeEnum} names
 * @param mimetypeColumn    the MIME type column
 * @param datetimeColumn    the column with the time the resource was taken
 * @param locationIdColumn  the file table's foreign key to the location table
 * @param locationTable     the table of the GPS locations
 * @param tagLinkTable      the link table between files and tags, or null when the backend has no tags
 * @param tagLinkFileColumn the link table's file id column
 * @param tagLinkTagColumn  the link table's tag id column
 * @param tagTable          the tag table
 * @param tagNameColumn     the tag name column of the tag table
 * @param thumbnailSubquery a SQL subquery answering the thumbnail id of the resource aliased {@code p} (for example
 *                          {@code (SELECT MIN(t.id) FROM thumb_files t WHERE t.target_file_id = p.id)}), or null when the backend has no thumbnails
 */
@Builder
public record MapQuerySchema(String fileTable, String idColumn, String aclIdColumn, String filenameColumn, String filePathColumn, String fileTypeColumn,
        String mimetypeColumn, String datetimeColumn, String locationIdColumn, String locationTable, String tagLinkTable, String tagLinkFileColumn,
        String tagLinkTagColumn, String tagTable, String tagNameColumn, String thumbnailSubquery) {

    public boolean hasTags() {
        return tagLinkTable != null
               && tagTable != null;
    }
}
