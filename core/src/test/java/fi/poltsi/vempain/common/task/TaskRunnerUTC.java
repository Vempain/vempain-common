package fi.poltsi.vempain.common.task;

import fi.poltsi.vempain.auth.service.UserDetailsImpl;
import fi.poltsi.vempain.common.api.TaskStatusEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunnerUTC {

	private final TaskProgressStore store  = new TaskProgressStore();
	private final List<Runnable>    queued = new ArrayList<>();
	private final TaskRunner        runner = new TaskRunner(store, queued::add);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void completedWorkStoresItsResultAndSteps() {
		var task = runner.submit("A", "title", 2, progress -> {
			progress.advance("one");
			progress.advance("two");
			return "result";
		});

		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.QUEUED);
		assertThat(store.find(task.getId())).isPresent();
		assertThat(queued).hasSize(1);

		queued.get(0)
			  .run();

		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
		assertThat(task.getResult()).isEqualTo("result");
		assertThat(task.getStartedAt()).isNotNull();
		assertThat(task.getFinishedAt()).isNotNull();
		assertThat(task.percent()).isEqualTo(100);
	}

	@Test
	void throwingWorkFailsTheTaskWithTheMessage() {
		var task = runner.submit("A", "title", 0, progress -> {
			throw new IllegalStateException("boom");
		});
		queued.get(0)
			  .run();

		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
		assertThat(task.getErrorMessage()).isEqualTo("boom");
	}

	@Test
	void exceptionWithoutMessageUsesTheExceptionName() {
		var task = runner.submit("A", "title", 0, progress -> {
			throw new NullPointerException();
		});
		queued.get(0)
			  .run();

		assertThat(task.getErrorMessage()).isEqualTo("NullPointerException");
	}

	@Test
	void securityContextOfTheSubmitterIsPropagatedToTheWorker() {
		var user = new UserDetailsImpl(5L, "alice", "Alice", "alice@nohost", "x", Set.of(), List.of());
		SecurityContextHolder.getContext()
							 .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
		var seen = new AtomicReference<Long>();

		var task = runner.submit("A", "title", 0, progress -> {
			seen.set(TaskRunner.currentUserId());
			return null;
		});
		SecurityContextHolder.clearContext();
		queued.get(0)
			  .run();

		assertThat(task.getOwnerId()).isEqualTo(5L);
		assertThat(seen.get()).isEqualTo(5L);
		assertThat(TaskRunner.currentUserId()).isNull();
	}

	@Test
	void workSubmittedInsideATransactionStartsAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		try {
			var task = runner.submit("A", "title", 0, progress -> "late");
			assertThat(queued).isEmpty();

			for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
				synchronization.afterCommit();
				synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
			}
			assertThat(queued).hasSize(1);
			queued.get(0)
				  .run();
			assertThat(task.getResult()).isEqualTo("late");
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void workSubmittedInsideARolledBackTransactionFails() {
		TransactionSynchronizationManager.initSynchronization();
		try {
			var task = runner.submit("A", "title", 0, progress -> "never");
			for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
				synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
			}
			assertThat(queued).isEmpty();
			assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void cancelBeforeStartSkipsTheWorkEntirely() {
		var ran = new AtomicReference<>(false);
		var task = runner.submit("A", "title", 0, progress -> {
			ran.set(true);
			return null;
		});

		assertThat(runner.cancel(task)).isTrue();
		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.QUEUED);
		assertThat(task.isCancelRequested()).isTrue();
		queued.get(0)
			  .run();

		assertThat(ran.get()).isFalse();
		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.CANCELLED);
		assertThat(task.getMessage()).isEqualTo("Cancelled before it started");
		assertThat(task.toResponse()
					   .isCancelRequested()).isTrue();
	}

	@Test
	void cancelDuringRunStopsAtTheNextCheckpointAndRevertsInReverseOrder() {
		var reverted = new ArrayList<String>();
		var task = runner.submit("A", "title", 3, progress -> {
			progress.registerCompensation("undo one", () -> reverted.add("one"));
			progress.advance("one");
			progress.registerCompensation("undo two", () -> reverted.add("two"));
			progress.advance("two");
			// The cancel request arrives while the second step is in flight
			assertThat(runner.cancel(progress)).isTrue();
			assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.CANCELLING);
			progress.checkpoint();
			reverted.add("never reached");
			return "unreachable";
		});
		queued.get(0)
			  .run();

		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.CANCELLED);
		assertThat(reverted).containsExactly("two", "one");
		assertThat(task.getRevertedSteps()
					   .get()).isEqualTo(2);
		assertThat(task.getMessage()).isEqualTo("Cancelled, reverted 2 changes");
		assertThat(task.getResult()).isNull();
		assertThat(task.percent()).isEqualTo(100);
		assertThat(runner.cancel(task)).isFalse();
	}

	@Test
	void failureAlsoRevertsAndReportsCompensationsThatFailed() {
		var reverted = new ArrayList<String>();
		var task = runner.submit("A", "title", 0, progress -> {
			progress.registerCompensation("undo ok", () -> reverted.add("ok"));
			progress.registerCompensation("undo broken", () -> {
				throw new IllegalStateException("cannot undo");
			});
			throw new IllegalStateException("boom");
		});
		queued.get(0)
			  .run();

		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
		assertThat(reverted).containsExactly("ok");
		assertThat(task.getRevertedSteps()
					   .get()).isEqualTo(1);
		assertThat(task.getErrorMessage()).isEqualTo("boom (1 changes could not be reverted)");
	}

	@Test
	void cancelWithoutRegisteredChangesSaysSo() {
		var task = runner.submit("A", "title", 0, progress -> {
			runner.cancel(progress);
			progress.checkpoint();
			return null;
		});
		queued.get(0)
			  .run();

		assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.CANCELLED);
		assertThat(task.getMessage()).isEqualTo("Cancelled, nothing to revert");
	}
}
