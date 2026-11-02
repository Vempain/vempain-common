package fi.poltsi.vempain.common.api;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import fi.poltsi.vempain.common.api.request.MapClusterFilesRequest;
import fi.poltsi.vempain.common.api.request.MapClusterRequest;
import fi.poltsi.vempain.common.api.response.MapClusterItem;
import fi.poltsi.vempain.common.api.response.MapClusterResponse;
import fi.poltsi.vempain.common.api.response.MapFileResponse;
import fi.poltsi.vempain.common.rest.MapAPI;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON contract of the map: snake_case fields, the stable base path, the optional filters. */
class MapContractJTC {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pathRemainsStable() {
        assertEquals("/map", MapAPI.BASE_PATH);
    }

    @Test
    void requestsAreSnakeCaseWithOptionalFilters() throws Exception {
        var cluster = objectMapper.readValue("{\"south\": 59.8, \"west\": 24.5, \"north\": 60.4, \"east\": 25.3, \"zoom\": 11, \"file_type\": \"IMAGE\", "
                + "\"from\": \"2026-01-01T00:00:00Z\", \"tags\": [\"summer\"], \"search\": \"IMG\"}", MapClusterRequest.class);
        assertEquals(59.8, cluster.getSouth());
        assertEquals(11, cluster.getZoom());
        assertEquals("IMAGE", cluster.getFileType());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), cluster.getFrom());
        assertNull(cluster.getTo());
        assertEquals(List.of("summer"), cluster.getTags());
        assertEquals("IMG", cluster.getSearch());
        assertEquals(Set.of("south", "west", "north", "east", "zoom", "file_type", "from", "to", "tags", "search"),
                new HashSet<>(objectMapper.readTree(objectMapper.writeValueAsString(cluster))
                    .propertyNames()));

        var files = objectMapper.readValue("{\"cell_latitude\": 60.17, \"cell_longitude\": 24.94, \"precision\": 2, \"limit\": 20}",
                MapClusterFilesRequest.class);
        assertEquals(60.17, files.getCellLatitude());
        assertEquals(2, files.getPrecision());
        assertEquals(20, files.getLimit());
        assertNull(files.getFileType());
    }

    @Test
    void responsesAreSnakeCase() throws Exception {
        var response = MapClusterResponse.builder()
            .precision(2)
            .clustered(true)
            .totalPoints(3)
            .truncated(false)
            .clusters(List.of(MapClusterItem.builder()
                .latitude(60.1712)
                .longitude(24.9415)
                .cellLatitude(60.17)
                .cellLongitude(24.94)
                .count(3)
                .build()))
            .build();
        var tree = objectMapper.readTree(objectMapper.writeValueAsString(response));
        assertEquals(Set.of("precision", "clustered", "total_points", "truncated", "clusters"), new HashSet<>(tree.propertyNames()));
        var cluster = tree.get("clusters")
            .get(0);
        assertEquals(Set.of("latitude", "longitude", "cell_latitude", "cell_longitude", "count", "file_id", "filename"),
                new HashSet<>(cluster.propertyNames()));
        assertTrue(cluster.get("file_id")
            .isNull());

        var file = objectMapper.readTree(objectMapper.writeValueAsString(MapFileResponse.builder()
            .id(5)
            .filename("a.jpg")
            .filePath("/p")
            .fileType("IMAGE")
            .mimetype("image/jpeg")
            .latitude(60.1)
            .longitude(24.9)
            .thumbnailId(9L)
            .build()));
        assertEquals(Set.of("id", "filename", "file_path", "file_type", "mimetype", "latitude", "longitude", "original_datetime", "thumbnail_id"),
                new HashSet<>(file.propertyNames()));
    }
}
