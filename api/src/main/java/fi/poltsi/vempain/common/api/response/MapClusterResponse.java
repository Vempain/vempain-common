package fi.poltsi.vempain.common.api.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The clusters of the GPS-tagged resources in a viewport, grouped by the backend at the precision that fits the requested zoom level.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Clusters of GPS-tagged resources in a map viewport")
public class MapClusterResponse {

    @Schema(description = "Number of decimals the coordinates were rounded to for the clustering (5 = single resources)", example = "2")
    private int precision;

    @Schema(description = "Whether the clusters merge several resources (precision below the point precision); false when every cluster is one resource",
            example = "true")
    private boolean clustered;

    @Schema(description = "Number of resources in the viewport matching the filters, summed over the clusters", example = "1280")
    private long totalPoints;

    @Schema(description = "Whether the list of clusters was cut at the backend's limit; zoom in to see the rest", example = "false")
    private boolean truncated;

    @Schema(description = "The clusters, largest first")
    private List<MapClusterItem> clusters;
}
