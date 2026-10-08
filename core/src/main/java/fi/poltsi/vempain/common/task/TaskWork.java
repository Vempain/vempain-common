package fi.poltsi.vempain.common.task;

/**
 * Body of a background task. The returned value becomes the task result exposed through the progress API; return {@code null}
 * when the task has no result payload. Throwing marks the task as failed with the exception message.
 *
 * @param <R> result type
 */
@FunctionalInterface
public interface TaskWork<R> {
	R run(TaskProgress progress) throws Exception;
}
