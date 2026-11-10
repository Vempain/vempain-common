package fi.poltsi.vempain.common.api.response;

import java.time.Instant;

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
 * A service API token without its secret.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "ApiTokenResponse", description = "Service API token (without the secret)")
public class ApiTokenResponse {

    private long id;
    private String tokenPrefix;
    private String description;
    private ApiTokenScopeEnum scope;
    private String network;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant expiresAt;
    private long ownerUserId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant created;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant lastUsed;
    private boolean expired;
}
