package fi.poltsi.vempain.common.task;

import fi.poltsi.vempain.common.api.response.TaskProgressResponse;
import fi.poltsi.vempain.common.rest.TaskAPI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TaskController implements TaskAPI {

	private final TaskProgressStore store;
	private final TaskRunner runner;

	@Override
	public ResponseEntity<List<TaskProgressResponse>> getTasks() {
		return ResponseEntity.ok(store.findByOwner(TaskRunner.currentUserId())
									  .stream()
									  .map(TaskProgress::toResponse)
									  .toList());
	}

	@Override
	public ResponseEntity<TaskProgressResponse> getTask(String taskId) {
		return ResponseEntity.ok(ownTask(taskId).toResponse());
	}

	@Override
	public ResponseEntity<TaskProgressResponse> cancelTask(String taskId) {
		var task = ownTask(taskId);
		if (!runner.cancel(task)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Task has already finished");
		}
		return ResponseEntity.accepted()
							 .body(task.toResponse());
	}

	@Override
	public ResponseEntity<Void> dismissTask(String taskId) {
		var task = ownTask(taskId);
		if (!task.isFinished()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Task is still running");
		}
		store.remove(taskId);
		return ResponseEntity.noContent()
							 .build();
	}

	private TaskProgress ownTask(String taskId) {
		var ownerId = TaskRunner.currentUserId();
		return store.find(taskId)
					.filter(task -> ownerId != null && ownerId.equals(task.getOwnerId()))
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
	}
}
