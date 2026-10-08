package fi.poltsi.vempain.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lifecycle states of a background task tracked by the task progress facility.
 * <pre>
 * QUEUED -> RUNNING -> COMPLETED | FAILED
 *        \         \-> CANCELLING -> CANCELLED
 *         \-> CANCELLED (cancelled before it started)
 * </pre>
 */
@Schema(description = "Lifecycle state of a background task", allowableValues = {"QUEUED", "RUNNING", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED"})
public enum TaskStatusEnum {
	QUEUED,
	RUNNING,
	/**
	 * Cancellation requested; the worker stops at its next checkpoint and reverts the changes made so far.
	 */
	CANCELLING,
	/**
	 * Stopped on request; every registered change was reverted.
	 */
	CANCELLED,
	COMPLETED,
	FAILED;

	public boolean isFinished() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}

	public boolean isActive() {
		return this == QUEUED || this == RUNNING || this == CANCELLING;
	}
}
