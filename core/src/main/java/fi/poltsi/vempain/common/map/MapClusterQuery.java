package fi.poltsi.vempain.common.map;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import fi.poltsi.vempain.common.api.request.MapClusterFilesRequest;
import fi.poltsi.vempain.common.api.request.MapClusterRequest;
import fi.poltsi.vempain.common.api.request.MapFilterRequest;
import fi.poltsi.vempain.common.api.response.MapClusterItem;
import fi.poltsi.vempain.common.api.response.MapClusterResponse;
import fi.poltsi.vempain.common.api.response.MapFileResponse;

/**
 * The clustering of GPS-tagged resources in the database, shared by the backends that host {@code MapAPI}: one implementation of the SQL, fed with the
 * backend's table and column names ({@link MapQuerySchema}) and the caller ({@link MapPrincipal}).
 *
 * <p>
 * The points are the readable resources with a location, their coordinates made signed from the stored value and hemisphere. The clusters of a viewport are the
 * points inside the bounds grouped by {@code ROUND(latitude, precision), ROUND(longitude, precision)} with the precision of the zoom level
 * ({@link MapClustering#precisionForZoom}); PostgreSQL does the grouping, so a map of hundreds of thousands of resources answers at most
 * {@link MapClustering#MAX_CLUSTERS} rows. The files of one cluster are the points of one such cell, newest first.
 * </p>
 *
 * <p>
 * Request text only ever reaches the SQL as bound parameters; the {@code LIKE} pattern of the file name search is escaped with an explicit escape character.
 * </p>
 */
public class MapClusterQuery {

    static final char LIKE_ESCAPE = '!';

    private final NamedParameterJdbcTemplate jdbc;
    private final MapQuerySchema schema;

    public MapClusterQuery(NamedParameterJdbcTemplate jdbc, MapQuerySchema schema) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.schema = Objects.requireNonNull(schema);
    }

    /** The clusters of the viewport of the request at the precision of its zoom level, largest first, for the principal */
    public MapClusterResponse clusters(MapClusterRequest request, MapPrincipal principal) {
        var precision = MapClustering.precisionForZoom(request.getZoom());
        if (!principal.isAuthenticated()) {
            return MapClusterResponse.builder()
                .precision(precision)
                .clustered(!MapClustering.isPointPrecision(precision))
                .totalPoints(0)
                .truncated(false)
                .clusters(List.of())
                .build();
        }

        var bounds = MapClustering.bounds(request);
        var parameters = filterParameters(request, principal);
        parameters.addValue("precision", precision);
        parameters.addValue("south", bounds.south());
        parameters.addValue("north", bounds.north());
        parameters.addValue("west", bounds.west());
        parameters.addValue("east", bounds.east());
        // one row more than the limit tells whether the list was cut
        parameters.addValue("maxClusters", MapClustering.MAX_CLUSTERS + 1);

        var rows = jdbc.query(clusterSql(request), parameters, (rs, rowNum) -> {
            var count = rs.getLong("cnt");
            return MapClusterItem.builder()
                .latitude(rs.getDouble("avg_lat"))
                .longitude(rs.getDouble("avg_lon"))
                .cellLatitude(rs.getDouble("cell_lat"))
                .cellLongitude(rs.getDouble("cell_lon"))
                .count(count)
                .fileId(count == 1 ? rs.getLong("min_id") : null)
                .filename(count == 1 ? rs.getString("min_filename") : null)
                .build();
        });

        var truncated = rows.size() > MapClustering.MAX_CLUSTERS;
        var clusters = truncated ? rows.subList(0, MapClustering.MAX_CLUSTERS) : rows;
        return MapClusterResponse.builder()
            .precision(precision)
            .clustered(!MapClustering.isPointPrecision(precision))
            .totalPoints(clusters.stream()
                .mapToLong(MapClusterItem::getCount)
                .sum())
            .truncated(truncated)
            .clusters(clusters)
            .build();
    }

    /** The resources of one cell, newest first, cut at the request's limit, for the principal */
    public List<MapFileResponse> files(MapClusterFilesRequest request, MapPrincipal principal) {
        if (!principal.isAuthenticated()) {
            return List.of();
        }

        var parameters = filterParameters(request, principal);
        parameters.addValue("precision", request.getPrecision());
        parameters.addValue("cellLat", MapClustering.cell(request.getCellLatitude(), request.getPrecision()));
        parameters.addValue("cellLon", MapClustering.cell(request.getCellLongitude(), request.getPrecision()));
        parameters.addValue("limit", MapClustering.fileLimit(request.getLimit()));

        return jdbc.query(filesSql(request), parameters, (rs, rowNum) -> {
            var taken = rs.getTimestamp("original_datetime");
            var thumbnailId = schema.thumbnailSubquery() == null ? null : rs.getObject("thumbnail_id", Long.class);
            return MapFileResponse.builder()
                .id(rs.getLong("id"))
                .filename(rs.getString("filename"))
                .filePath(rs.getString("file_path"))
                .fileType(rs.getString("file_type"))
                .mimetype(rs.getString("mimetype"))
                .latitude(rs.getDouble("lat"))
                .longitude(rs.getDouble("lon"))
                .originalDatetime(taken == null ? null : taken.toInstant())
                .thumbnailId(thumbnailId)
                .build();
        });
    }

    /** The SQL of the clusters of a viewport; package-private for the tests */
    String clusterSql(MapFilterRequest filter) {
        // The cells are computed once in the CTE: PostgreSQL cannot tell two bound :precision placeholders apart, so grouping by the expression itself would
        // not match the selected expression
        return "WITH p AS ("
                + pointsSql(filter)
                + ") "
                + "SELECT p.cell_lat, p.cell_lon, COUNT(*) AS cnt, AVG(p.lat) AS avg_lat, AVG(p.lon) AS avg_lon, "
                + "MIN(p.id) AS min_id, MIN(p.filename) AS min_filename "
                + "FROM p WHERE p.lat BETWEEN :south AND :north AND p.lon BETWEEN :west AND :east "
                + "GROUP BY p.cell_lat, p.cell_lon "
                + "ORDER BY cnt DESC, p.cell_lat, p.cell_lon LIMIT :maxClusters";
    }

    /** The SQL of the resources of one cell; package-private for the tests */
    String filesSql(MapFilterRequest filter) {
        var thumbnail = schema.thumbnailSubquery() == null ? "" : ", " + schema.thumbnailSubquery() + " AS thumbnail_id";
        return "WITH p AS ("
                + pointsSql(filter)
                + ") "
                + "SELECT p.id, p.filename, p.file_path, p.file_type, p.mimetype, p.original_datetime, p.lat, p.lon"
                + thumbnail
                + " "
                + "FROM p WHERE p.cell_lat = :cellLat AND p.cell_lon = :cellLon "
                + "ORDER BY p.original_datetime DESC NULLS LAST, p.id DESC LIMIT :limit";
    }

    /**
     * The readable, filtered resources with signed coordinates: the common table expression both queries build on. The ACL check is the one every Vempain
     * listing applies: a row of the resource's ACL grants read to the user or to one of the user's units.
     */
    private String pointsSql(MapFilterRequest filter) {
        var sql = new StringBuilder();
        sql.append("SELECT f.")
            .append(schema.idColumn())
            .append(" AS id, f.")
            .append(schema.filenameColumn())
            .append(" AS filename, f.")
            .append(schema.filePathColumn())
            .append(" AS file_path, f.")
            .append(schema.fileTypeColumn())
            .append(" AS file_type, f.")
            .append(schema.mimetypeColumn())
            .append(" AS mimetype, f.")
            .append(schema.datetimeColumn())
            .append(" AS original_datetime, ")
            .append("CASE WHEN g.latitude_ref = 'S' THEN -g.latitude ELSE g.latitude END AS lat, ")
            .append("CASE WHEN g.longitude_ref = 'W' THEN -g.longitude ELSE g.longitude END AS lon, ")
            .append("ROUND(CASE WHEN g.latitude_ref = 'S' THEN -g.latitude ELSE g.latitude END, :precision) AS cell_lat, ")
            .append("ROUND(CASE WHEN g.longitude_ref = 'W' THEN -g.longitude ELSE g.longitude END, :precision) AS cell_lon ")
            .append("FROM ")
            .append(schema.fileTable())
            .append(" f JOIN ")
            .append(schema.locationTable())
            .append(" g ON g.id = f.")
            .append(schema.locationIdColumn())
            .append(" WHERE f.")
            .append(schema.aclIdColumn())
            .append(" IN (SELECT a.acl_id FROM acl a WHERE a.read_privilege = TRUE AND (a.user_id = :userId OR a.unit_id IN (:unitIds)))");

        if (hasText(filter.getFileType())) {
            sql.append(" AND f.")
                .append(schema.fileTypeColumn())
                .append(" = :fileType");
        }
        if (filter.getFrom() != null) {
            sql.append(" AND f.")
                .append(schema.datetimeColumn())
                .append(" >= :from");
        }
        if (filter.getTo() != null) {
            sql.append(" AND f.")
                .append(schema.datetimeColumn())
                .append(" <= :to");
        }
        if (hasText(filter.getSearch())) {
            sql.append(" AND LOWER(f.")
                .append(schema.filenameColumn())
                .append(") LIKE :search ESCAPE '")
                .append(LIKE_ESCAPE)
                .append("'");
        }
        if (hasTags(filter)) {
            if (!schema.hasTags()) {
                // the backend keeps no tags: a tag filter matches nothing rather than everything
                sql.append(" AND 1 = 0");
            } else {
                sql.append(" AND f.")
                    .append(schema.idColumn())
                    .append(" IN (SELECT tl.")
                    .append(schema.tagLinkFileColumn())
                    .append(" FROM ")
                    .append(schema.tagLinkTable())
                    .append(" tl JOIN ")
                    .append(schema.tagTable())
                    .append(" t ON t.id = tl.")
                    .append(schema.tagLinkTagColumn())
                    .append(" WHERE LOWER(t.")
                    .append(schema.tagNameColumn())
                    .append(") IN (:tags))");
            }
        }
        return sql.toString();
    }

    private static MapSqlParameterSource filterParameters(MapFilterRequest filter, MapPrincipal principal) {
        var parameters = new MapSqlParameterSource();
        parameters.addValue("userId", principal.userId());
        // an empty IN list is not valid SQL; -1 is never a unit id
        parameters.addValue("unitIds", principal.unitIds() == null
                                       || principal.unitIds()
                                           .isEmpty() ? List.of(-1L) : new ArrayList<>(principal.unitIds()));
        if (hasText(filter.getFileType())) {
            parameters.addValue("fileType", filter.getFileType()
                .trim()
                .toUpperCase(Locale.ROOT));
        }
        if (filter.getFrom() != null) {
            parameters.addValue("from", Timestamp.from(filter.getFrom()));
        }
        if (filter.getTo() != null) {
            parameters.addValue("to", Timestamp.from(filter.getTo()));
        }
        if (hasText(filter.getSearch())) {
            parameters.addValue("search", "%"
                    + escapeLike(filter.getSearch()
                        .trim()
                        .toLowerCase(Locale.ROOT))
                    + "%");
        }
        if (hasTags(filter)) {
            parameters.addValue("tags", filter.getTags()
                .stream()
                .filter(MapClusterQuery::hasText)
                .map(tag -> tag.trim()
                    .toLowerCase(Locale.ROOT))
                .distinct()
                .toList());
        }
        return parameters;
    }

    /** Escapes the LIKE wildcards and the escape character itself so that the search text matches literally */
    static String escapeLike(String text) {
        var escaped = new StringBuilder(text.length());
        for (var c : text.toCharArray()) {
            if (c == '%'
                || c == '_'
                || c == LIKE_ESCAPE) {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static boolean hasTags(MapFilterRequest filter) {
        return filter.getTags() != null
               && filter.getTags()
                   .stream()
                   .anyMatch(MapClusterQuery::hasText);
    }

    private static boolean hasText(String value) {
        return value != null
               && !value.isBlank();
    }

    /** Converts an instant for the tests of the hosting backends */
    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
