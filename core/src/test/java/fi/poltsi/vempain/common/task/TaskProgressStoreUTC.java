package fi.poltsi.vempain.common.task;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskProgressStoreUTC {

	private final TaskProgressStore store = new TaskProgressStore();

	@Test
	void createRegistersAQueuedTaskWithAnOwner() {
		var task = store.create("SCAN_DIRECTORIES", "Scan /images", 7L, 3);

		assertThat(task.getId()).isNotBlank();
		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.QUEUED);
		assertThat(task.getOwnerId()).isEqualTo(7L);
		assertThat(task.getTotalSteps()
					   .get()).isEqualTo(3);
		assertThat(store.find(task.getId())).contains(task);
		assertThat(store.find("missing")).isEmpty();
		assertThat(store.find(null)).isEmpty();
	}

	@Test
	void findByOwnerReturnsOnlyTheOwnersTasksNewestFirst() throws InterruptedException {
		var first = store.create("A", "first", 1L, 0);
		Thread.sleep(2);
		var second = store.create("B", "second", 1L, 0);
		store.create("C", "other", 2L, 0);

		assertThat(store.findByOwner(1L)).containsExactly(second, first);
		assertThat(store.findByOwner(2L)).hasSize(1);
		assertThat(store.findByOwner(null)).isEmpty();
	}

	@Test
	void progressReportingAndPercentage() {
		var task = store.create("A", "steps", 1L, 4);

		task.start();
		task.advance("one");
		task.advanceFailed("two failed");
		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.RUNNING);
		assertThat(task.getCompletedSteps()
					   .get()).isEqualTo(2);
		assertThat(task.getFailedSteps()
					   .get()).isEqualTo(1);
		assertThat(task.percent()).isEqualTo(50);
		assertThat(task.getMessage()).isEqualTo("two failed");

		task.message("waiting");
		assertThat(task.getMessage()).isEqualTo("waiting");

		task.complete("done");
		assertThat(task.isFinished()).isTrue();
		assertThat(task.percent()).isEqualTo(100);
		assertThat(task.getCompletedSteps()
					   .get()).isEqualTo(4);
		assertThat(task.getResult()).isEqualTo("done");
		assertThat(task.getFinishedAt()).isNotNull();

		var response = task.toResponse();
		assertThat(response.getTaskId()).isEqualTo(task.getId());
		assertThat(response.getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
		assertThat(response.getPercent()).isEqualTo(100);
		assertThat(response.getResult()).isEqualTo("done");
		assertThat(task.toAcceptedResponse()
					   .getTotalSteps()).isEqualTo(4);
	}

	@Test
	void percentIsZeroWithoutKnownStepsAndNeverReachesHundredWhileRunning() {
		var task = store.create("A", "unknown", 1L, 0);
		task.start();
		assertThat(task.percent()).isZero();
		task.setTotalSteps(2);
		task.advance("a");
		task.advance("b");
		assertThat(task.percent()).isEqualTo(99);
		task.fail("boom");
		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
		assertThat(task.getErrorMessage()).isEqualTo("boom");
		assertThat(task.percent()).isEqualTo(100);
	}

	@Test
	void statusHelpersDistinguishActiveAndFinishedStates() {
		assertThat(TaskStatusEnum.QUEUED.isActive()).isTrue();
		assertThat(TaskStatusEnum.RUNNING.isActive()).isTrue();
		assertThat(TaskStatusEnum.CANCELLING.isActive()).isTrue();
		assertThat(TaskStatusEnum.CANCELLED.isFinished()).isTrue();
		assertThat(TaskStatusEnum.COMPLETED.isFinished()).isTrue();
		assertThat(TaskStatusEnum.FAILED.isFinished()).isTrue();
		assertThat(TaskStatusEnum.CANCELLING.isFinished()).isFalse();
	}

	@Test
	void evictionRemovesOnlyFinishedTasksOlderThanTheCutoff() {
		var running  = store.create("A", "running", 1L, 0);
		var finished = store.create("A", "finished", 1L, 0);
		finished.complete(null);
		running.start();

		assertThat(store.evictFinishedBefore(Instant.now()
													.minus(Duration.ofMinutes(5)))).isZero();
		assertThat(store.evictFinishedBefore(Instant.now()
													.plus(Duration.ofSeconds(1)))).isEqualTo(1);
		assertThat(store.find(running.getId())).isPresent();
		assertThat(store.find(finished.getId())).isEmpty();
		assertThat(store.remove(running.getId())).isTrue();
		assertThat(store.remove(running.getId())).isFalse();
	}

	@Test
	void scheduledEvictionHonoursTheSchedulingFlagAndRetention() {
		var finished = store.create("A", "finished", 1L, 0);
		finished.complete(null);
		ReflectionTestUtils.setField(store, "schedulingEnabled", false);
		ReflectionTestUtils.setField(store, "retentionMinutes", 0L);
		store.evictExpired();
		assertThat(store.size()).isEqualTo(1);

		ReflectionTestUtils.setField(store, "schedulingEnabled", true);
		ReflectionTestUtils.setField(store, "retentionMinutes", -1L);
		store.evictExpired();
		assertThat(store.size()).isZero();
	}
}
