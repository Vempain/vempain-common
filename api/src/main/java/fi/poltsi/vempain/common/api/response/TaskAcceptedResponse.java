package fi.poltsi.vempain.common.api.response;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Returned with HTTP 202 by every endpoint that starts a background task. The client follows the task through
 * {@code GET /tasks/{task_id}}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Acknowledgement of a background task that was accepted for execution")
public class TaskAcceptedResponse {
	@Schema(description = "Task identifier used to poll the progress", example = "4f1c2d7e-9a0b-4c3d-8e2f-1a2b3c4d5e6f",
			requiredMode = Schema.RequiredMode.REQUIRED)
	private String         taskId;
	@Schema(description = "Task type, see the TaskTypeEnum of the service", example = "PUBLISH_FILE_GROUP", requiredMode = Schema.RequiredMode.REQUIRED)
	private String         type;
	@Schema(description = "Human readable title of the task", example = "Publish file group /images/vacation", requiredMode = Schema.RequiredMode.REQUIRED)
	private String         title;
	@Schema(description = "Status at the time of acceptance", example = "QUEUED", requiredMode = Schema.RequiredMode.REQUIRED)
	private TaskStatusEnum status;
	@Schema(description = "Number of steps the task will perform when known, otherwise 0", example = "42")
	private long           totalSteps;
}
