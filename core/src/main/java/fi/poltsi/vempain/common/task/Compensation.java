package fi.poltsi.vempain.common.task;

/**
 * Undo action for one change a task has made. Registered through {@link TaskProgress#registerCompensation(String, Compensation)}
 * and executed in reverse order when the task is cancelled or fails.
 */
@FunctionalInterface
public interface Compensation {
	void revert() throws Exception;
}
