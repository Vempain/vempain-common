package fi.poltsi.vempain.common.api;

import fi.poltsi.vempain.common.api.request.CopyrightRequest;
import fi.poltsi.vempain.common.api.request.LocationRequest;
import fi.poltsi.vempain.common.api.request.TagRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for request JSON names. The strings stay explicit so a mapper or annotation regression fails immediately;
 * the same keys are consumed by the admin and file backends and mirrored by the TypeScript models.
 */
class RequestContractJTC {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void tagRequestUsesSnakeCaseForEveryPublicField() throws Exception {
		var request = new TagRequest(17L, "tag", "tag-de", "tag-en", "tag-es", "tag-fi", "tag-sv");

		assertThat(objectMapper.writeValueAsString(request))
				.isEqualTo("{\"id\":17,\"tag_name\":\"tag\",\"tag_name_de\":\"tag-de\",\"tag_name_en\":\"tag-en\",\"tag_name_es\":\"tag-es\","
						   + "\"tag_name_fi\":\"tag-fi\",\"tag_name_sv\":\"tag-sv\"}");
	}

	@Test
	void tagRequestReadsSnakeCasePayload() throws Exception {
		var request = objectMapper.readValue(
				"{\"id\":17,\"tag_name\":\"tag\",\"tag_name_de\":\"tag-de\",\"tag_name_en\":\"tag-en\",\"tag_name_es\":\"tag-es\",\"tag_name_fi\":\"tag-fi\","
				+ "\"tag_name_sv\":\"tag-sv\"}",
				TagRequest.class);

		assertThat(request.getTagName()).isEqualTo("tag");
		assertThat(request.getTagNameDe()).isEqualTo("tag-de");
		assertThat(request.getTagNameSv()).isEqualTo("tag-sv");
	}

	@Test
	void copyrightRequestUsesSnakeCase() throws Exception {
		var request = CopyrightRequest.builder()
									  .rightsHolder("Holder")
									  .rightsTerms("Terms")
									  .rightsUrl("https://example.com/rights")
									  .creatorName("Creator")
									  .creatorEmail("creator@example.com")
									  .creatorCountry("Finland")
									  .creatorUrl("https://example.com/creator")
									  .build();

		// Jackson 3 serialises properties alphabetically, so compare trees instead of strings
		assertThat(objectMapper.readTree(objectMapper.writeValueAsString(request)))
				.isEqualTo(objectMapper.readTree("{\"rights_holder\":\"Holder\",\"rights_terms\":\"Terms\",\"rights_url\":\"https://example.com/rights\","
												 + "\"creator_name\":\"Creator\",\"creator_email\":\"creator@example.com\",\"creator_country\":\"Finland\","
												 + "\"creator_url\":\"https://example.com/creator\"}"));
	}

	@Test
	void locationRequestUsesSnakeCase() throws Exception {
		var request = LocationRequest.builder()
									 .latitude(new BigDecimal("60.17000"))
									 .latitudeRef('N')
									 .longitude(new BigDecimal("24.93800"))
									 .longitudeRef('E')
									 .satelliteCount(7)
									 .subLocation("Kaartinkaupunki")
									 .build();

		assertThat(objectMapper.readTree(objectMapper.writeValueAsString(request)).propertyNames())
				.containsExactlyInAnyOrder("latitude", "latitude_ref", "longitude", "longitude_ref", "altitude", "direction", "satellite_count",
										   "country", "state", "city", "street", "sub_location");
	}
}
