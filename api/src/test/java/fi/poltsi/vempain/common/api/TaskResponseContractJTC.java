package fi.poltsi.vempain.common.api;

import fi.poltsi.vempain.common.api.response.TaskAcceptedResponse;
import fi.poltsi.vempain.common.api.response.TaskProgressResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSON contract of the task progress DTOs: the admin and file frontends share one TypeScript model for them.
 */
class TaskResponseContractJTC {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void acceptedResponseUsesSnakeCase() throws Exception {
		var accepted = TaskAcceptedResponse.builder()
										   .taskId("task-1")
										   .type("PUBLISH_PAGE")
										   .title("Publish page")
										   .status(TaskStatusEnum.QUEUED)
										   .totalSteps(3)
										   .build();

		var json = objectMapper.readTree(objectMapper.writeValueAsString(accepted));

		assertThat(json.propertyNames()).containsExactlyInAnyOrder("task_id", "type", "title", "status", "total_steps");
		assertThat(json.get("status").asString()).isEqualTo("QUEUED");
	}

	@Test
	void progressResponseUsesSnakeCaseAndCarriesTheResult() throws Exception {
		var progress = TaskProgressResponse.builder()
										   .taskId("task-1")
										   .type("PUBLISH_PAGE")
										   .title("Publish page")
										   .status(TaskStatusEnum.COMPLETED)
										   .totalSteps(3)
										   .completedSteps(3)
										   .failedSteps(0)
										   .percent(100)
										   .cancelRequested(false)
										   .revertedSteps(0)
										   .message("Published")
										   .result(Map.of("identifier", "music_library"))
										   .createdAt(Instant.parse("2026-10-06T10:00:00Z"))
										   .startedAt(Instant.parse("2026-10-06T10:00:01Z"))
										   .finishedAt(Instant.parse("2026-10-06T10:02:13Z"))
										   .build();

		var json = objectMapper.readTree(objectMapper.writeValueAsString(progress));

		assertThat(json.propertyNames()).containsExactlyInAnyOrder("task_id", "type", "title", "status", "total_steps", "completed_steps", "failed_steps",
																  "percent", "cancel_requested", "reverted_steps", "message", "error_message", "result",
																  "created_at", "started_at", "finished_at");
		assertThat(json.get("result").get("identifier").asString()).isEqualTo("music_library");
		assertThat(json.get("created_at").asString()).isEqualTo("2026-10-06T10:00:00Z");
	}

	@Test
	void statusHelpersDistinguishActiveAndFinishedStates() {
		assertThat(TaskStatusEnum.QUEUED.isActive()).isTrue();
		assertThat(TaskStatusEnum.CANCELLING.isActive()).isTrue();
		assertThat(TaskStatusEnum.CANCELLING.isFinished()).isFalse();
		assertThat(TaskStatusEnum.CANCELLED.isFinished()).isTrue();
		assertThat(TaskStatusEnum.COMPLETED.isFinished()).isTrue();
		assertThat(TaskStatusEnum.FAILED.isFinished()).isTrue();
	}
}
