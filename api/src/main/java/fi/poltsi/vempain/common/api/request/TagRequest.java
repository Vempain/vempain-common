package fi.poltsi.vempain.common.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Request DTO for creating or updating a tag")
public class TagRequest {
	@Nullable
	@Schema(description = "Tag ID, for new this should be left as empty, when updating it must be populated", example = "123", requiredMode =
			Schema.RequiredMode.NOT_REQUIRED)
	private Long id;

	@NotBlank
	@Schema(description = "Tag name (default language)", example = "example-tag", requiredMode = Schema.RequiredMode.REQUIRED)
	private String tagName;

	@NotBlank
	@Schema(description = "Tag name in German", example = "beispiel-tag", requiredMode = Schema.RequiredMode.REQUIRED)
	private String tagNameDe;

	@NotBlank
	@Schema(description = "Tag name in English", example = "example-tag", requiredMode = Schema.RequiredMode.REQUIRED)
	private String tagNameEn;

	@NotBlank
	@Schema(description = "Tag name in Spanish", example = "ejemplo-etiqueta", requiredMode = Schema.RequiredMode.REQUIRED)
	private String tagNameEs;

	@NotBlank
	@Schema(description = "Tag name in Finnish", example = "esimerkki-tagi", requiredMode = Schema.RequiredMode.REQUIRED)
	private String tagNameFi;

	@NotBlank
	@Schema(description = "Tag name in Swedish", example = "exempel-tagg", requiredMode = Schema.RequiredMode.REQUIRED)
	private String tagNameSv;
}
