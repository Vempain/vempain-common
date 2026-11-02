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
 * Asks for the resources of one cluster: the cell the cluster response named ({@code cell_latitude}, {@code cell_longitude} at {@code precision}) and the same
 * filters the clusters were asked with.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Request of the resources of one map cluster")
public class MapClusterFilesRequest extends MapFilterRequest {

    @Schema(description = "Latitude of the cluster's cell as the cluster response gave it", example = "60.17", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(-90)
    @Max(90)
    private Double cellLatitude;

    @Schema(description = "Longitude of the cluster's cell as the cluster response gave it", example = "24.94", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(-180)
    @Max(180)
    private Double cellLongitude;

    @Schema(description = "Number of decimals the cell coordinates are rounded to, as the cluster response gave it", example = "2",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @Min(0)
    @Max(5)
    private int precision;

    @Schema(description = "At most this many resources, newest first; the backend caps it at its listing limit", example = "50",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Min(1)
    @Max(500)
    private Integer limit;
}
