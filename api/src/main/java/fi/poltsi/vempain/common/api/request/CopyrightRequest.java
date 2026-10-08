package fi.poltsi.vempain.common.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "CopyrightRequest", description = "Copyright information request")
public class CopyrightRequest {

	@Schema(description = "Copyright holder name", example = "John Doe")
	private String rightsHolder;
	@Schema(description = "Copyright terms", example = "All rights reserved")
	private String rightsTerms;
	@Schema(description = "Copyright URL", example = "https://example.com/copyright")
	private String rightsUrl;
	@Schema(description = "Creator name", example = "John Doe")
	private String creatorName;
	@Schema(description = "Creator email", example = "someone@somewhere.tld")
	private String creatorEmail;
	@Schema(description = "Creator country", example = "Finland")
	private String creatorCountry;
	@Schema(description = "Creator URL", example = "https://example.com/creator")
	private String creatorUrl;
}
