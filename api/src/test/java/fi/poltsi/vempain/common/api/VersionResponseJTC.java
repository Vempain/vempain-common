package fi.poltsi.vempain.common.api;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import fi.poltsi.vempain.common.api.response.VersionResponse;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionResponseJTC {

    @Test
    void serializesSnakeCaseContract() throws Exception {
        var response = VersionResponse.builder()
            .version("1.2.3")
            .service("vempain-file-backend")
            .build();

        var json = new ObjectMapper().writeValueAsString(response);

        var object = new JSONObject(json);
        assertEquals("1.2.3", object.getString("version"));
        assertEquals("vempain-file-backend", object.getString("service"));
    }
}
