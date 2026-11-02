package fi.poltsi.vempain.common.api.request;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The filters of the map of GPS-tagged resources, shared by the cluster request and the request for the files of one cluster: a file type, a range of the time
 * the resource was taken, tags the resource carries and a text searched in the file name. Every filter is optional; absent filters keep every readable resource
 * that has coordinates.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Filters of the map of GPS-tagged resources")
public class MapFilterRequest {

    @Schema(description = "Only resources of this file type (a FileTypeEnum name); every type when absent", example = "IMAGE",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Size(max = 50)
    private String fileType;

    @Schema(description = "Only resources taken at or after this time (the original date and time of the resource)", example = "2026-01-01T00:00:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant from;

    @Schema(description = "Only resources taken at or before this time", example = "2026-12-31T23:59:59Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant to;

    @Schema(description = "Only resources carrying at least one of these tags (case insensitive)", example = "[\"summer\", \"finland\"]",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Size(max = 50)
    private List<@Size(max = 256) String> tags;

    @Schema(description = "Only resources whose file name contains this text (case insensitive)", example = "IMG_20",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Size(max = 255)
    private String search;
}
