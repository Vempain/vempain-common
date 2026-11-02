package fi.poltsi.vempain.common.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One cluster of the map: the resources whose coordinates round to the same cell at the response's precision. The marker is drawn at the centroid; the cell
 * coordinates identify the cluster in a {@code MapClusterFilesRequest}. A cluster of one resource names it, so a client need not ask for its files.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "A cluster of GPS-tagged resources")
public class MapClusterItem {

    @Schema(description = "Latitude of the centroid of the clustered resources, signed degrees", example = "60.1712")
    private double latitude;

    @Schema(description = "Longitude of the centroid of the clustered resources, signed degrees", example = "24.9415")
    private double longitude;

    @Schema(description = "Latitude of the cell, the coordinate rounded to the precision", example = "60.17")
    private double cellLatitude;

    @Schema(description = "Longitude of the cell, the coordinate rounded to the precision", example = "24.94")
    private double cellLongitude;

    @Schema(description = "Number of resources in the cluster", example = "42")
    private long count;

    @Schema(description = "ID of the resource when the cluster holds exactly one, null otherwise", example = "1234",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long fileId;

    @Schema(description = "File name of the resource when the cluster holds exactly one, null otherwise", example = "IMG_2001.jpg",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String filename;
}
