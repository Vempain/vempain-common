package fi.poltsi.vempain.common.api.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A GPS-tagged resource of a map cluster, with what the map needs to list it, open its details and show its thumbnail. On the file backend the resource is an
 * original file, on the admin backend a published site file; the hosting service decides what {@code thumbnail_id} refers to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "A GPS-tagged resource of a map cluster")
public class MapFileResponse {

    @Schema(description = "ID of the resource in the answering service", example = "1234")
    private long id;

    @Schema(description = "File name", example = "IMG_2001.jpg")
    private String filename;

    @Schema(description = "Directory path of the file", example = "/2026/summer")
    private String filePath;

    @Schema(description = "File type (FileTypeEnum name)", example = "IMAGE")
    private String fileType;

    @Schema(description = "MIME type", example = "image/jpeg")
    private String mimetype;

    @Schema(description = "Latitude of the resource, signed degrees", example = "60.17123")
    private double latitude;

    @Schema(description = "Longitude of the resource, signed degrees", example = "24.94156")
    private double longitude;

    @Schema(description = "When the resource was taken, null when unknown", example = "2026-06-21T12:00:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant originalDatetime;

    @Schema(description = "ID of the thumbnail the answering service can show for the resource, null when it has none", example = "5678",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long thumbnailId;
}
