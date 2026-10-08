package fi.poltsi.vempain.common.api;

import fi.poltsi.vempain.common.api.response.LocationResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseContractJTC {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void locationResponseCarriesTheIdAndTheInheritedSnakeCaseFields() throws Exception {
		var response = LocationResponse.builder()
									   .id(42L)
									   .latitude(new BigDecimal("60.17000"))
									   .latitudeRef('N')
									   .longitude(new BigDecimal("24.93800"))
									   .longitudeRef('E')
									   .city("Helsinki")
									   .build();

		var json = objectMapper.readTree(objectMapper.writeValueAsString(response));

		assertThat(json.get("id").asLong()).isEqualTo(42L);
		assertThat(json.get("latitude_ref").asString()).isEqualTo("N");
		assertThat(json.get("city").asString()).isEqualTo("Helsinki");
		assertThat(json.has("latitudeRef")).isFalse();
	}

	@Test
	void locationResponseReadsSnakeCasePayload() throws Exception {
		var response = objectMapper.readValue(
				"{\"id\":42,\"latitude\":60.17,\"latitude_ref\":\"N\",\"longitude\":24.938,\"longitude_ref\":\"E\",\"sub_location\":\"Centre\"}",
				LocationResponse.class);

		assertThat(response.getId()).isEqualTo(42L);
		assertThat(response.getSubLocation()).isEqualTo("Centre");
		assertThat(response.getLongitudeRef()).isEqualTo('E');
	}
}
