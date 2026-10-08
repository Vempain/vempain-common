package fi.poltsi.vempain.common.task;

import fi.poltsi.vempain.common.task.entity.TaskCompensationEntity;

/**
 * Bridge between the durable task records and the services of the hosting application. Every service that uses the task
 * facility provides exactly one Spring bean implementing this interface: it maps the task type and JSON payload of a claimed
 * task to a service call, and replays serialized compensations when a task is cancelled or fails. Implementations should
 * resolve the services through the Spring proxy (for example {@code applicationContext.getBean(...)}) so that transactional
 * methods are executed correctly on the worker thread.
 */
public interface TaskCommandExecutor {

	/**
	 * Executes the work of a claimed task.
	 *
	 * @param progress the task; its {@link TaskProgress#getType()} and {@link TaskProgress#getPayload()} identify the work
	 * @return the result payload exposed through the progress API, or {@code null}
	 * @throws TaskCancelledException when the task was cancelled at a checkpoint
	 * @throws Exception              when the work failed; the runner marks the task FAILED and reverts its compensations
	 */
	Object execute(TaskProgress progress) throws Exception;

	/**
	 * Replays one durable compensation registered through {@link TaskProgress#registerDurableCompensation(String, String, Object)}.
	 *
	 * @throws IllegalArgumentException when the command type is unknown to this service
	 */
	void compensate(TaskCompensationEntity compensation) throws Exception;
}
