package fi.poltsi.vempain.common.rest;

import fi.poltsi.vempain.common.api.response.VersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

@Tag(name = "VersionAPI", description = "Backend version information")
public interface VersionAPI {
	String BASE_PATH = "/version";

	@Operation(summary = "Get backend version", description = "Returns the version of the authenticated Vempain backend")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Backend version",
						 content = @Content(schema = @Schema(implementation = VersionResponse.class), mediaType = MediaType.APPLICATION_JSON_VALUE)),
			@ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content)
	})
	@SecurityRequirement(name = "******")
	@GetMapping(path = BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<VersionResponse> getVersion();
}
