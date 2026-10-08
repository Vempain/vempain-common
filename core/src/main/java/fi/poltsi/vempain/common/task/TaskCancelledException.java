package fi.poltsi.vempain.common.task;

/**
 * Thrown by {@link TaskProgress#checkpoint()} when the owner has requested cancellation. Task bodies let it propagate; the
 * {@link TaskRunner} then reverts the registered changes and marks the task CANCELLED.
 */
public class TaskCancelledException extends RuntimeException {
	public TaskCancelledException() {
		super("Task cancelled");
	}
}
