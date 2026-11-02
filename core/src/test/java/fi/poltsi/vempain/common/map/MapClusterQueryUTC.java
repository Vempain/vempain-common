package fi.poltsi.vempain.common.map;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import fi.poltsi.vempain.common.api.request.MapClusterFilesRequest;
import fi.poltsi.vempain.common.api.request.MapClusterRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SQL the shared query builds for a backend's schema and the parameters it binds; the grouping itself runs in PostgreSQL and is covered by the controller
 * tests of the hosting backends.
 */
@ExtendWith(MockitoExtension.class)
class MapClusterQueryUTC {

    private static final MapQuerySchema SCHEMA = MapQuerySchema.builder()
        .fileTable("files")
        .idColumn("id")
        .aclIdColumn("acl_id")
        .filenameColumn("filename")
        .filePathColumn("file_path")
        .fileTypeColumn("file_type")
        .mimetypeColumn("mimetype")
        .datetimeColumn("original_datetime")
        .locationIdColumn("gps_location_id")
        .locationTable("gps_locations")
        .tagLinkTable("file_tags")
        .tagLinkFileColumn("file_id")
        .tagLinkTagColumn("tag_id")
        .tagTable("tags")
        .tagNameColumn("tag_name")
        .thumbnailSubquery("(SELECT MIN(t.id) FROM thumb_files t WHERE t.target_file_id = p.id)")
        .build();

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private MapClusterQuery query;

    @BeforeEach
    void setUp() {
        query = new MapClusterQuery(jdbc, SCHEMA);
    }

    @SuppressWarnings("unchecked")
    @Test
    void clustersGroupTheReadablePointsOfTheViewportAtThePrecisionOfTheZoom() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        var request = MapClusterRequest.builder()
            .south(59.0)
            .north(61.0)
            .west(24.0)
            .east(26.0)
            .zoom(8)
            .fileType("image")
            .from(Instant.parse("2026-01-01T00:00:00Z"))
            .to(Instant.parse("2026-12-31T00:00:00Z"))
            .tags(List.of("Summer", " finland", ""))
            .search("IMG_%")
            .build();

        var response = query.clusters(request, new MapPrincipal(7L, Set.of(3L, 4L)));

        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("FROM files f JOIN gps_locations g ON g.id = f.gps_location_id")
            .contains("f.acl_id IN (SELECT a.acl_id FROM acl a WHERE a.read_privilege = TRUE AND (a.user_id = :userId OR a.unit_id IN (:unitIds)))")
            .contains("CASE WHEN g.latitude_ref = 'S' THEN -g.latitude ELSE g.latitude END AS lat")
            .contains("AND f.file_type = :fileType")
            .contains("AND f.original_datetime >= :from")
            .contains("AND f.original_datetime <= :to")
            .contains("AND LOWER(f.filename) LIKE :search ESCAPE '!'")
            .contains("IN (SELECT tl.file_id FROM file_tags tl JOIN tags t ON t.id = tl.tag_id WHERE LOWER(t.tag_name) IN (:tags))")
            .contains("ROUND(CASE WHEN g.latitude_ref = 'S' THEN -g.latitude ELSE g.latitude END, :precision) AS cell_lat")
            .contains("GROUP BY p.cell_lat, p.cell_lon")
            .contains("p.lat BETWEEN :south AND :north AND p.lon BETWEEN :west AND :east")
            .contains("LIMIT :maxClusters");
        var values = parameters.getValue()
            .getValues();
        assertThat(values).containsEntry("userId", 7L)
            .containsEntry("precision", 2)
            .containsEntry("fileType", "IMAGE")
            .containsEntry("search", "%img!_!%%")
            .containsEntry("tags", List.of("summer", "finland"))
            .containsEntry("south", 59.0)
            .containsEntry("north", 61.0)
            .containsEntry("maxClusters", MapClustering.MAX_CLUSTERS + 1);
        assertThat((List<Long>) values.get("unitIds")).containsExactlyInAnyOrder(3L, 4L);
        assertThat(response.getPrecision()).isEqualTo(2);
        assertThat(response.isClustered()).isTrue();
        assertThat(response.getClusters()).isEmpty();
        assertThat(response.getTotalPoints()).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    void filtersAreLeftOutWhenAbsentAndAnEmptyUnitListBindsASentinel() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        var request = MapClusterRequest.builder()
            .south(59.0)
            .north(61.0)
            .west(24.0)
            .east(26.0)
            .zoom(17)
            .search("   ")
            .tags(List.of(" "))
            .build();

        var response = query.clusters(request, new MapPrincipal(7L, Set.of()));

        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).doesNotContain(":fileType")
            .doesNotContain(":from")
            .doesNotContain(":search")
            .doesNotContain(":tags");
        assertThat(parameters.getValue()
            .getValues()).containsEntry("unitIds", List.of(-1L))
            .containsEntry("precision", 5)
            .doesNotContainKey("search");
        assertThat(response.isClustered()).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void filesOfACellBindTheRoundedCellAndTheCappedLimit() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        var request = MapClusterFilesRequest.builder()
            .cellLatitude(60.17123)
            .cellLongitude(24.94567)
            .precision(2)
            .limit(9999)
            .build();

        query.files(request, new MapPrincipal(7L, Set.of()));

        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("(SELECT MIN(t.id) FROM thumb_files t WHERE t.target_file_id = p.id) AS thumbnail_id")
            .contains("WHERE p.cell_lat = :cellLat AND p.cell_lon = :cellLon")
            .contains("ORDER BY p.original_datetime DESC NULLS LAST, p.id DESC LIMIT :limit");
        assertThat(parameters.getValue()
            .getValues()).containsEntry("cellLat", 60.17)
            .containsEntry("cellLon", 24.95)
            .containsEntry("limit", MapClustering.MAX_FILE_LIMIT);
    }

    @SuppressWarnings("unchecked")
    @Test
    void anonymousCallersGetNothingWithoutAQuery() {
        var clusters = query.clusters(MapClusterRequest.builder()
            .south(0.0)
            .north(1.0)
            .west(0.0)
            .east(1.0)
            .zoom(3)
            .build(), MapPrincipal.anonymous());
        var files = query.files(MapClusterFilesRequest.builder()
            .cellLatitude(0.0)
            .cellLongitude(0.0)
            .precision(1)
            .build(), MapPrincipal.anonymous());

        assertThat(clusters.getClusters()).isEmpty();
        assertThat(files).isEmpty();
        verify(jdbc, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void aBackendWithoutTagsMatchesNothingForATagFilterAndHasNoThumbnailColumn() {
        var withoutTags = new MapClusterQuery(jdbc, MapQuerySchema.builder()
            .fileTable("site_file")
            .idColumn("id")
            .aclIdColumn("acl_id")
            .filenameColumn("file_name")
            .filePathColumn("file_path")
            .fileTypeColumn("file_type")
            .mimetypeColumn("mime_type")
            .datetimeColumn("original_datetime")
            .locationIdColumn("location_id")
            .locationTable("gps_location")
            .build());

        var sql = withoutTags.clusterSql(MapClusterRequest.builder()
            .tags(List.of("summer"))
            .build());
        assertThat(sql).contains(" AND 1 = 0");
        assertThat(withoutTags.filesSql(MapClusterFilesRequest.builder()
            .build())).doesNotContain("thumbnail_id");
        assertThat(MapClusterQuery.escapeLike("50%_done!")).isEqualTo("50!%!_done!!");
    }
}
