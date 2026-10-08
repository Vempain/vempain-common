package fi.poltsi.vempain.common.task;

import fi.poltsi.vempain.common.task.entity.TaskCompensationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRunnerDurableUTC {

	@Mock
	private TaskProgressStore   store;
	@Mock
	private TaskCommandExecutor commandExecutor;

	private TaskRunner runner;

	@BeforeEach
	void setUp() {
		runner = new TaskRunner(store, Runnable::run);
		ReflectionTestUtils.setField(runner, "commandExecutor", commandExecutor);
	}

	@Test
	void pollsAndExecutesAClaimedTask() throws Exception {
		var progress = progress("task-1");
		when(store.claimNext(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.of(progress));
		when(commandExecutor.execute(progress)).thenReturn("result");

		runner.poll();

		assertThat(progress.getStatus()).isEqualTo(fi.poltsi.vempain.common.api.TaskStatusEnum.COMPLETED);
		assertThat(progress.getResult()).isEqualTo("result");
		verify(store).claimNext(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void cancelsBeforeCommandExecutionWhenCancellationWasRequested() throws Exception {
		var progress = progress("task-2");
		progress.cancelRequested.set(true);

		ReflectionTestUtils.invokeMethod(runner, "executeDurable", progress);

		assertThat(progress.getStatus()).isEqualTo(fi.poltsi.vempain.common.api.TaskStatusEnum.CANCELLED);
		assertThat(progress.getMessage()).isEqualTo("Cancelled before it started");
	}

	@Test
	void failureRevertsDurableCompensationsAndReportsTheError() throws Exception {
		var progress     = progress("task-3");
		var compensation = new TaskCompensationEntity();
		compensation.setId(1L);
		compensation.setDescription("undo");
		when(store.durableCompensations(progress)).thenReturn(List.of(compensation));
		doThrow(new IllegalStateException("broken")).when(commandExecutor)
		                                            .execute(progress);

		ReflectionTestUtils.invokeMethod(runner, "executeDurable", progress);

		assertThat(progress.getStatus()).isEqualTo(fi.poltsi.vempain.common.api.TaskStatusEnum.FAILED);
		assertThat(progress.getErrorMessage()).isEqualTo("broken");
		verify(commandExecutor).compensate(compensation);
		verify(store).markCompensationDone(compensation);
	}

	@Test
	void compensationFailureIsIncludedInTheTaskError() throws Exception {
		var progress     = progress("task-4");
		var compensation = new TaskCompensationEntity();
		compensation.setId(2L);
		compensation.setDescription("cannot undo");
		when(store.durableCompensations(progress)).thenReturn(List.of(compensation));
		doThrow(new IllegalStateException("work failed")).when(commandExecutor)
		                                                 .execute(progress);
		doThrow(new IllegalStateException("undo failed")).when(commandExecutor)
		                                                 .compensate(compensation);

		ReflectionTestUtils.invokeMethod(runner, "executeDurable", progress);

		assertThat(progress.getErrorMessage()).isEqualTo("work failed (1 changes could not be reverted)");
	}

	private TaskProgress progress(String id) {
		var progress = new TaskProgress(id, "SCAN_DIRECTORIES", "title", 7L, 1);
		progress.attach(store, "{}", "worker");
		return progress;
	}
}
