package fi.poltsi.vempain.common.api.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Network suggestion used by token-management screens.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "ApiTokenNetworkResponse", description = "Suggested network for a new API token")
public class ApiTokenNetworkResponse {

    public enum Source {
        CONFIGURED,
        PRIVATE_NETWORK,
        NONE
    }

    private String network;
    private Source source;
    private List<Candidate> candidates;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "ApiTokenNetworkCandidate", description = "A private network the backend is attached to")
    public static class Candidate {

        private String network;
        private String interfaceName;
        private String address;
    }
}
