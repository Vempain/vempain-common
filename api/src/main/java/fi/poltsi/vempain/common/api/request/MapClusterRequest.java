package fi.poltsi.vempain.common.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Asks for the clusters of GPS-tagged resources inside the visible part of the map at a zoom level. The backend groups the points by their coordinates rounded
 * to the number of decimals that fits the zoom level ({@code MapClustering.precisionForZoom} of {@code vempain-common-core}) and answers one cluster per cell;
 * at the highest precision every cluster is a single resource.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Request of the clusters of GPS-tagged resources in a map viewport")
public class MapClusterRequest extends MapFilterRequest {

    @Schema(description = "Southern edge of the viewport, signed degrees of latitude", example = "59.8", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(-90)
    @Max(90)
    private Double south;

    @Schema(description = "Western edge of the viewport, signed degrees of longitude", example = "24.5", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(-180)
    @Max(180)
    private Double west;

    @Schema(description = "Northern edge of the viewport, signed degrees of latitude", example = "60.4", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(-90)
    @Max(90)
    private Double north;

    @Schema(description = "Eastern edge of the viewport, signed degrees of longitude", example = "25.3", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(-180)
    @Max(180)
    private Double east;

    @Schema(description = "Zoom level of the map (0 = the whole world, 18 = street level); decides how coarsely the points are clustered", example = "11",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @Min(0)
    @Max(22)
    private int zoom;
}
