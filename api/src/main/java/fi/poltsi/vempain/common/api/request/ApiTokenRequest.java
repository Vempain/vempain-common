package fi.poltsi.vempain.common.api.request;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fi.poltsi.vempain.common.api.ApiTokenScopeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Common request contract for creating a service API token.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "ApiTokenRequest", description = "New service API token")
public class ApiTokenRequest {

    @Schema(description = "What the token is used for", example = "vempain-mcp", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 255)
    private String description;

    @Schema(description = "IPv4 or IPv6 network the token may be used from, in CIDR notation; a plain address means a single host (/32 or /128)",
            example = "10.0.0.0/24", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String network;

    @Schema(description = "When the token stops working; must be in the future", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Future
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant expiresAt;

    @Schema(description = "Token scope; the hosting backend validates the scopes it supports", example = "MCP_READ")
    private ApiTokenScopeEnum scope;
}
