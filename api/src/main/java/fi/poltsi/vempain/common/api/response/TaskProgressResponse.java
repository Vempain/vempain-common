package fi.poltsi.vempain.common.api.response;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

/**
 * Progress snapshot of a background task. The result payload depends on the task type (see {@code TaskTypeEnum}) and is only
 * present once the task has completed. A cancelled task has reverted the changes it made before the cancellation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Progress snapshot of a background task")
public class TaskProgressResponse {
	@Schema(description = "Task identifier", example = "4f1c2d7e-9a0b-4c3d-8e2f-1a2b3c4d5e6f", requiredMode = Schema.RequiredMode.REQUIRED)
	private String         taskId;
	@Schema(description = "Task type, see the TaskTypeEnum of the service", example = "SCAN_DIRECTORIES", requiredMode = Schema.RequiredMode.REQUIRED)
	private String         type;
	@Schema(description = "Human readable title of the task", example = "Scan /images/vacation", requiredMode = Schema.RequiredMode.REQUIRED)
	private String         title;
	@Schema(description = "Current status", example = "RUNNING", requiredMode = Schema.RequiredMode.REQUIRED)
	private TaskStatusEnum status;
	@Schema(description = "Number of steps the task performs, 0 when unknown", example = "42")
	private long           totalSteps;
	@Schema(description = "Steps finished so far, successful or failed", example = "17")
	private long           completedSteps;
	@Schema(description = "Steps that failed", example = "1")
	private long           failedSteps;
	@Schema(description = "Completion percentage 0..100; 100 once the task has finished", example = "40")
	private int            percent;
	@Schema(description = "Whether the owner asked for the task to be cancelled", example = "false")
	private boolean cancelRequested;
	@Schema(description = "Number of changes that were reverted when the task was cancelled or failed", example = "3")
	private long    revertedSteps;
	@Schema(description = "Description of the current or last step", example = "Uploading IMG_0017.jpg")
	private String         message;
	@Schema(description = "Error description when the task failed", example = "Admin backend rejected the upload")
	private String         errorMessage;
	@Schema(description = "Type specific result, present when the task completed; the task type (see the TaskTypeEnum of the service) documents its "
						  + "payload")
	private Object         result;
	@Schema(description = "When the task was accepted", example = "2026-10-06T10:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
	private Instant        createdAt;
	@Schema(description = "When the task started running", example = "2026-10-06T10:00:01Z")
	private Instant        startedAt;
	@Schema(description = "When the task finished", example = "2026-10-06T10:02:13Z")
	private Instant        finishedAt;
}
