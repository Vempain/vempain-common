package fi.poltsi.vempain.common.task;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import fi.poltsi.vempain.common.task.entity.TaskCompensationEntity;
import fi.poltsi.vempain.common.task.entity.TaskRecordEntity;
import fi.poltsi.vempain.common.task.repository.TaskCompensationRepository;
import fi.poltsi.vempain.common.task.repository.TaskRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskProgressStoreDurableUTC {

	@Mock
	private TaskRecordRepository       repository;
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
		var created   = store.create("SCAN_DIRECTORIES", "Scan", 7L, 2, java.util.Map.of("path", "/photos"));
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
}
