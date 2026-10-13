package fi.poltsi.vempain.common.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class VersionControllerUTC {

	@Test
	void answersTheConfiguredServiceNameAndVersion() {
		var response = new VersionController("vempain-file", "1.2.3").getVersion();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()
						   .getService()).isEqualTo("vempain-file");
		assertThat(response.getBody()
						   .getVersion()).isEqualTo("1.2.3");
	}
}
