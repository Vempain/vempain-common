package fi.poltsi.vempain.common.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Version information for a Vempain backend")
public class VersionResponse {

    @Schema(description = "Application version", example = "1.2.3", requiredMode = Schema.RequiredMode.REQUIRED)
    private String version;

    @Schema(description = "Backend service identifier", example = "vempain-file-backend", requiredMode = Schema.RequiredMode.REQUIRED)
    private String service;
}
