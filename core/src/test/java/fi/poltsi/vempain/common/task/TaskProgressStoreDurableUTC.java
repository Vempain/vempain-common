package fi.poltsi.vempain.common.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import fi.poltsi.vempain.common.task.entity.TaskCompensationEntity;
import fi.poltsi.vempain.common.task.entity.TaskRecordEntity;
import fi.poltsi.vempain.common.task.repository.TaskCompensationRepository;
import fi.poltsi.vempain.common.task.repository.TaskRecordRepository;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskProgressStoreDurableUTC {

    @Mock
    private TaskRecordRepository repository;
    @Mock
    private TaskCompensationRepository compensationRepository;

    private TaskProgressStore store;

    @BeforeEach
    void setUp() {
        store = new TaskProgressStore(repository, compensationRepository, new ObjectMapper());
        ReflectionTestUtils.setField(store, "leaseSeconds", 60L);
    }

    @Test
    void createsFindsAndListsPersistedTasks() {
        var created = store.create("SCAN_DIRECTORIES", "Scan", 7L, 2, java.util.Map.of("path", "/photos"));
        var persisted = capturedRecord();
        when(repository.findById(created.getId())).thenReturn(Optional.of(persisted));

        assertThat(created.getPayload()).contains("\"path\":\"/photos\"");
        assertThat(store.find(created.getId())).contains(created);
        assertThat(store.find(null)).isEmpty();

        persisted.setOwnerId(7L);
        persisted.setCreatedAt(Instant.now());
        when(repository.findAll()).thenReturn(List.of(persisted));
        assertThat(store.findByOwner(7L)).hasSize(1);
        assertThat(store.findByOwner(null)).isEmpty();
        verify(repository).save(any(TaskRecordEntity.class));
    }

    @Test
    void claimsRefreshesCancelsAndHeartbeatsTasks() {
        var entity = entity("task-1", 7L, TaskStatusEnum.QUEUED);
        entity.setPayload("{\"value\":1}");
        when(repository.claimNext("worker-a", 60L)).thenReturn(entity);
        var claimed = store.claimNext("worker-a")
            .orElseThrow();

        assertThat(claimed.getId()).isEqualTo("task-1");
        assertThat(claimed.getWorkerId()).isEqualTo("worker-a");
        when(repository.requestCancel("task-1")).thenReturn(1);
        assertThat(store.requestCancel(claimed)).isTrue();
        assertThat(claimed.isCancelRequested()).isTrue();
        store.heartbeat(claimed);
        verify(repository).heartbeat("task-1", "worker-a", 60L);

        entity.setStatus(TaskStatusEnum.CANCELLING);
        when(repository.findById("task-1")).thenReturn(Optional.of(entity));
        store.refreshCancellation(claimed);
        assertThat(claimed.getStatus()).isEqualTo(TaskStatusEnum.CANCELLING);
    }

    @Test
    void savesOnlyForTheCurrentWorkerAndPersistsCompensations() {
        var entity = entity("task-2", 7L, TaskStatusEnum.RUNNING);
        entity.setWorkerId("worker-a");
        when(repository.findById("task-2")).thenReturn(Optional.of(entity));
        var progress = claimedProgress("task-2", "worker-a");
        progress.advance("updated");
        verify(repository).save(entity);

        reset(repository);
        when(repository.findById("task-2")).thenReturn(Optional.of(entity));
        var stale = claimedProgress("task-2", "worker-b");
        stale.advance("stale");
        verify(repository, never()).save(any(TaskRecordEntity.class));

        progress.registerDurableCompensation("undo", "TAG_REVERT_MUTATION", java.util.Map.of("file_id", 1L));
        var compensation = new TaskCompensationEntity();
        compensation.setId(3L);
        when(compensationRepository.findByTaskIdOrderByIdDesc("task-2")).thenReturn(List.of(compensation));
        assertThat(store.durableCompensations(progress)).containsExactly(compensation);
        store.markCompensationDone(compensation);
        assertThat(compensation.isCompleted()).isTrue();
        verify(compensationRepository).save(compensation);
    }

    @Test
    void progressAndCompensationsAreCommittedInTheirOwnTransaction() {
        var transactionManager = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class);
        var definition = ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        when(transactionManager.getTransaction(definition.capture())).thenReturn(status);
        var transactional = new TaskProgressStore(repository, compensationRepository, new ObjectMapper(), transactionManager);
        ReflectionTestUtils.setField(transactional, "leaseSeconds", 60L);
        var entity = entity("task-3", 7L, TaskStatusEnum.RUNNING);
        entity.setWorkerId("worker-a");
        when(repository.findById("task-3")).thenReturn(Optional.of(entity));
        when(repository.claimNext("worker-a", 60L)).thenReturn(entity);
        var progress = transactional.claimNext("worker-a")
            .orElseThrow();

        progress.advance("first file");
        progress.registerDurableCompensation("undo", "REVERT", java.util.Map.of("file_id", 1L));

        assertThat(definition.getAllValues()).hasSize(2)
            .allSatisfy(seen -> assertThat(seen.getPropagationBehavior())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactionManager, org.mockito.Mockito.times(2)).commit(status);
        verify(repository).save(entity);
        assertThat(entity.getCompletedSteps()).isEqualTo(1);
        verify(compensationRepository).save(any(TaskCompensationEntity.class));
    }

    @Test
    void removesAndEvictsPersistedTasks() {
        when(repository.existsById("task-3")).thenReturn(true);
        assertThat(store.remove("task-3")).isTrue();
        verify(repository).deleteById("task-3");
        when(repository.deleteFinishedBefore(any())).thenReturn(2);
        assertThat(store.evictFinishedBefore(Instant.now())).isEqualTo(2);
        when(repository.count()).thenReturn(4L);
        assertThat(store.size()).isEqualTo(4);
    }

    private TaskRecordEntity capturedRecord() {
        var captor = ArgumentCaptor.forClass(TaskRecordEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private TaskRecordEntity entity(String id, Long owner, TaskStatusEnum status) {
        var entity = new TaskRecordEntity();
        entity.setId(id);
        entity.setOwnerId(owner);
        entity.setType("SCAN_DIRECTORIES");
        entity.setTitle("title");
        entity.setPayload("{}");
        entity.setStatus(status);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private TaskProgress claimedProgress(String id, String workerId) {
        var progress = new TaskProgress(id, "SCAN_DIRECTORIES", "title", 7L, 1);
        progress.attach(store, "{}", workerId);
        return progress;
    }

    @Test
    void coversTheRemainingDurableBranches() {
        var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        var autowired = new TaskProgressStore(repository, compensationRepository, new ObjectMapper(), provider);
        assertThat(autowired.isDurable()).isTrue();

        // find: a task that is not cached locally is rebuilt from the row, a cached one is refreshed in place
        var entity = entity("task-9", 7L, TaskStatusEnum.RUNNING);
        entity.setResult("{\"count\":3}");
        when(repository.findById("task-9")).thenReturn(Optional.of(entity));
        var fresh = store.find("task-9")
            .orElseThrow();
        assertThat(fresh.getResult()).isEqualTo(java.util.Map.of("count", 3));
        entity.setWorkerId("worker-a");
        when(repository.claimNext("worker-a", 60L)).thenReturn(entity);
        var claimed = store.claimNext("worker-a")
            .orElseThrow();
        assertThat(store.claimNext("worker-a")
            .orElseThrow()).isSameAs(claimed);
        assertThat(store.find("task-9")
            .orElseThrow()).isSameAs(claimed);

        // remove: unknown ids are reported, known ones deleted
        when(repository.existsById("unknown")).thenReturn(false);
        assertThat(store.remove("unknown")).isFalse();
        when(repository.existsById("task-9")).thenReturn(true);
        assertThat(store.remove("task-9")).isTrue();
        verify(repository).deleteById("task-9");

        // requestCancel: a running task moves to CANCELLING when the row accepted the request
        var runningRow = entity("task-10", 7L, TaskStatusEnum.RUNNING);
        runningRow.setWorkerId("worker-a");
        when(repository.findById("task-10")).thenReturn(Optional.of(runningRow));
        var running = claimedProgress("task-10", "worker-a");
        running.start();
        when(repository.requestCancel("task-10")).thenReturn(1);
        assertThat(store.requestCancel(running)).isTrue();
        assertThat(running.getStatus()).isEqualTo(TaskStatusEnum.CANCELLING);
        when(repository.requestCancel("task-11")).thenReturn(0);
        assertThat(store.requestCancel(claimedProgress("task-11", "worker-a"))).isFalse();

        // a result of a finished task is serialised into the row
        running.complete(java.util.Map.of("published", 2));
        var saved = ArgumentCaptor.forClass(TaskRecordEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()
            .stream()
            .anyMatch(row -> "{\"published\":2}".equals(row.getResult()))).isTrue();
    }

    @Test
    void inMemoryStoreHasNoDurableCompensationsAndNoClaims() {
        var local = new TaskProgressStore();
        var progress = local.create("SCAN", "Scan", 1L, 1);
        assertThat(local.isDurable()).isFalse();
        assertThat(local.claimNext("worker")).isEmpty();
        local.registerCompensation(progress, "undo", "CMD", java.util.Map.of());
        assertThat(local.durableCompensations(progress)).isEmpty();
        assertThat(local.requestCancel(progress)).isTrue();
        assertThat(local.remove("missing")).isFalse();
    }
}
