package fi.poltsi.vempain.common.task;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fi.poltsi.vempain.auth.entity.UserAccount;
import fi.poltsi.vempain.auth.repository.UserAccountRepository;
import fi.poltsi.vempain.auth.service.UserDetailsImpl;
import fi.poltsi.vempain.common.api.TaskStatusEnum;
import fi.poltsi.vempain.common.task.entity.TaskCompensationEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The production constructor, the durable submit paths and the owner context restore of {@link TaskRunner}.
 */
@ExtendWith(MockitoExtension.class)
class TaskRunnerLifecycleUTC {

    @Mock
    private TaskCommandExecutor commandExecutor;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private TaskProgressStore store;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void productionRunnerOwnsItsExecutorAndRestoresTheOwnerContextOnTheWorker() throws Exception {
        var account = UserAccount.builder()
            .id(42L)
            .loginName("owner")
            .password("x")
            .build();
        // The worker thread invokes these stubs; Mockito's strict stubbing check would flag the cross-thread calls as mismatches
        // The runner passes the boxed owner id; the repository also declares a primitive overload, so the cast selects the right one
        lenient().when(userAccountRepository.findById((Long) 42L))
            .thenReturn(Optional.of(account));
        var progress = TaskProgress.unmanaged("t1", "SCAN", "Scan", 42L, 1, "{}");
        lenient().when(store.claimNext(any()))
            .thenReturn(Optional.of(progress))
            .thenReturn(Optional.empty());
        var seenPrincipal = new AtomicReference<Object>();
        lenient().when(commandExecutor.execute(progress))
            .thenAnswer(invocation -> {
                var authentication = SecurityContextHolder.getContext()
                    .getAuthentication();
                seenPrincipal.set(authentication == null ? null : authentication.getPrincipal());
                return "ok";
            });
        var runner = new TaskRunner(store, commandExecutor, userAccountRepository, 1);

        runner.start();
        var deadline = System.currentTimeMillis() + 5_000;
        while (!progress.isFinished()
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        runner.shutdown();

        assertThat(progress.getErrorMessage()).isNull();
        assertThat(seenPrincipal.get()).isInstanceOf(UserDetailsImpl.class);
        assertThat(((UserDetailsImpl) seenPrincipal.get()).getId()).isEqualTo(42L);
        assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
        assertThat(progress.getResult()).isEqualTo("ok");
    }

    @Test
    void durableSubmitPollsImmediatelyOutsideATransaction() {
        var runner = new TaskRunner(store, Runnable::run);
        var progress = TaskProgress.unmanaged("t2", "SCAN", "Scan", null, 1, "{}");
        when(store.isDurable()).thenReturn(true);
        when(store.create("SCAN", "Scan", null, 1, Map.of("k", "v"))).thenReturn(progress);

        var submitted = runner.submitDurable("SCAN", "Scan", 1, Map.of("k", "v"), p -> null);

        assertThat(submitted).isSameAs(progress);
        // without a command executor the poll is a no-op, but the submit answered with the stored task
        verify(store, never()).claimNext(any());
    }

    @Test
    void durableSubmitInsideATransactionPollsAfterCommitAndDropsTheTaskOnRollback() {
        var queued = new java.util.ArrayList<Runnable>();
        var runner = new TaskRunner(store, queued::add);
        var progress = TaskProgress.unmanaged("t3", "SCAN", "Scan", null, 1, "{}");
        when(store.create("SCAN", "Scan", null, 1, Map.of())).thenReturn(progress);

        TransactionSynchronizationManager.initSynchronization();
        runner.submit("SCAN", "Scan", 1, Map.of());
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        assertThat(queued).hasSize(1);
        queued.getFirst()
            .run();
        TransactionSynchronizationManager.clearSynchronization();

        TransactionSynchronizationManager.initSynchronization();
        var rolledBack = TaskProgress.unmanaged("t4", "SCAN", "Scan", null, 1, "{}");
        when(store.create("SCAN", "Scan", null, 1, Map.of())).thenReturn(rolledBack);
        runner.submit("SCAN", "Scan", 1, Map.of());
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clearSynchronization();

        verify(store).remove("t4");
        assertThat(rolledBack.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
        assertThat(queued).hasSize(1);
    }

    @Test
    void cancelledDurableTaskRevertsAndSkipsCompletedCompensations() throws Exception {
        var runner = new TaskRunner(store, Runnable::run);
        org.springframework.test.util.ReflectionTestUtils.setField(runner, "commandExecutor", commandExecutor);
        var progress = TaskProgress.unmanaged("t5", "SCAN", "Scan", null, 2, "{}");
        var done = new TaskCompensationEntity();
        done.setId(1L);
        done.setCompleted(true);
        var pending = new TaskCompensationEntity();
        pending.setId(2L);
        pending.setDescription("undo");
        when(store.claimNext(any())).thenReturn(Optional.of(progress))
            .thenReturn(Optional.empty());
        when(store.durableCompensations(progress)).thenReturn(List.of(done, pending));
        when(commandExecutor.execute(progress)).thenThrow(new TaskCancelledException());

        runner.poll();

        verify(commandExecutor, never()).compensate(done);
        verify(commandExecutor).compensate(pending);
        verify(store).markCompensationDone(pending);
        assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.CANCELLED);
        assertThat(progress.getMessage()).isEqualTo("Cancelled, reverted 1 change");
    }

    @Test
    void cancelMessageCountsCompensationsThatCouldNotBeReverted() throws Exception {
        var runner = new TaskRunner(store, Runnable::run);
        org.springframework.test.util.ReflectionTestUtils.setField(runner, "commandExecutor", commandExecutor);
        var progress = TaskProgress.unmanaged("t6", "SCAN", "Scan", null, 2, "{}");
        var first = new TaskCompensationEntity();
        first.setId(1L);
        var second = new TaskCompensationEntity();
        second.setId(2L);
        when(store.claimNext(any())).thenReturn(Optional.of(progress))
            .thenReturn(Optional.empty());
        when(store.durableCompensations(progress)).thenReturn(List.of(first, second));
        when(commandExecutor.execute(progress)).thenThrow(new TaskCancelledException());
        doThrow(new IllegalStateException("gone")).when(commandExecutor)
            .compensate(first);

        runner.poll();

        assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.CANCELLED);
        assertThat(progress.getMessage()).isEqualTo("Cancelled, reverted 1 change, 1 could not be reverted");
    }

    @Test
    void currentUserIdReadsTheVempainPrincipalOnly() {
        assertThat(TaskRunner.currentUserId()).isNull();
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken("anonymous", null, List.of()));
        assertThat(TaskRunner.currentUserId()).isNull();
        var principal = new UserDetailsImpl(9L, "nine", "Nine", "nine@nohost.nodomain", "pw", Set.of(), List.of());
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        assertThat(TaskRunner.currentUserId()).isEqualTo(9L);
    }

    @Test
    void cancelRequestIsLoggedAndAnsweredByTheProgress() {
        var runner = new TaskRunner(store, Runnable::run);
        var progress = TaskProgress.unmanaged("t7", "SCAN", "Scan", null, 1, "{}");
        // an unmanaged task has no store: the local request is answered directly
        assertThat(runner.cancel(progress)).isTrue();
        assertThat(progress.isCancelRequested()).isTrue();
        assertThat(progress.isDurable()).isFalse();
    }
}
