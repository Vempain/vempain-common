package fi.poltsi.vempain.common.controller;

import fi.poltsi.vempain.common.api.response.VersionResponse;
import fi.poltsi.vempain.common.rest.VersionAPI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController implements VersionAPI {
	private final String name;
	private final String version;

	public VersionController(@Value("${vempain.name}") String name, @Value("${vempain.version}") String version) {
		this.name    = name;
		this.version = version;
	}

	@Override
	public ResponseEntity<VersionResponse> getVersion() {
		return ResponseEntity.ok(VersionResponse.builder()
												.version(version)
												.service(name)
												.build());
	}
}
