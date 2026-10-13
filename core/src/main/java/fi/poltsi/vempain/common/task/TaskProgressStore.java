package fi.poltsi.vempain.common.task;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import fi.poltsi.vempain.common.task.entity.TaskCompensationEntity;
import fi.poltsi.vempain.common.task.entity.TaskRecordEntity;
import fi.poltsi.vempain.common.task.repository.TaskCompensationRepository;
import fi.poltsi.vempain.common.task.repository.TaskRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable task registry. The small in-memory map is only a compatibility cache for unit tests and
 * currently executing work; all production task state is read from PostgreSQL.
 * <p>
 * Progress reports and compensations are written in a transaction of their own ({@code REQUIRES_NEW}): a task body usually runs
 * inside a transaction of the hosting service, and the steps it reports must be visible to the polling clients while that
 * transaction is still open, not only once it commits.
 */
@Slf4j
@Component
public class TaskProgressStore {
	private final Map<String, TaskProgress>  localTasks = new ConcurrentHashMap<>();
	private final TaskRecordRepository       repository;
	private final TaskCompensationRepository compensationRepository;
	private final ObjectMapper               objectMapper;
	private final TransactionTemplate progressTransaction;

	@Value("${vempain.scheduling.enabled:true}")
	private boolean schedulingEnabled;
	@Value("${vempain.tasks.retention-minutes:120}")
	private long retentionMinutes;
	@Value("${vempain.tasks.lease-seconds:60}")
	private long leaseSeconds;

	public TaskProgressStore() {
		this(null, null, null, (PlatformTransactionManager) null);
	}

	/**
	 * Durable store whose progress writes join the caller's transaction; meant for unit tests of the store itself.
	 */
	public TaskProgressStore(TaskRecordRepository repository, TaskCompensationRepository compensationRepository, ObjectMapper objectMapper) {
		this(repository, compensationRepository, objectMapper, (PlatformTransactionManager) null);
	}

	@Autowired
	public TaskProgressStore(TaskRecordRepository repository, TaskCompensationRepository compensationRepository, ObjectMapper objectMapper,
							 ObjectProvider<PlatformTransactionManager> transactionManager) {
		this(repository, compensationRepository, objectMapper, transactionManager.getIfAvailable());
	}

	/**
	 * @param transactionManager the manager of the datasource holding the task tables; progress writes run in a new transaction of
	 *                           it so that they commit independently of the task body. Null joins the surrounding transaction.
	 */
	public TaskProgressStore(TaskRecordRepository repository, TaskCompensationRepository compensationRepository, ObjectMapper objectMapper,
							 PlatformTransactionManager transactionManager) {
		this.repository             = repository;
		this.compensationRepository = compensationRepository;
		this.objectMapper           = objectMapper;
		this.progressTransaction    = transactionManager == null ? null : newProgressTransaction(transactionManager);
	}

	private static TransactionTemplate newProgressTransaction(PlatformTransactionManager transactionManager) {
		var template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	/**
	 * Runs a progress write in its own transaction when a transaction manager is configured; otherwise in the calling context.
	 */
	private void inOwnTransaction(Runnable write) {
		if (progressTransaction == null) {
			write.run();
		} else {
			progressTransaction.executeWithoutResult(status -> write.run());
		}
	}

	boolean isDurable() {
		return repository != null;
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	public TaskProgress create(String type, String title, Long ownerId, long totalSteps) {
		return create(type, title, ownerId, totalSteps, Map.of());
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	public TaskProgress create(String type, String title, Long ownerId, long totalSteps, Object payload) {
		var progress = new TaskProgress(UUID.randomUUID()
		                                    .toString(), type, title, ownerId, totalSteps);
		progress.attach(this, serialize(payload), null);
		localTasks.put(progress.getId(), progress);
		if (repository != null) {
			repository.save(toEntity(progress));
		}
		return progress;
	}

	@Transactional(readOnly = true)
	public Optional<TaskProgress> find(String taskId) {
		if (taskId == null) {
			return Optional.empty();
		}
		if (repository == null) {
			return Optional.ofNullable(localTasks.get(taskId));
		}
		return repository.findById(taskId)
		                 .map(entity -> {
							 var fresh = fromEntity(entity);
							 var local = localTasks.get(taskId);
							 if (local != null) {
								 local.updateFrom(fresh);
								 return local;
							 }
							 return fresh;
						 });
	}

	@Transactional(readOnly = true)
	public List<TaskProgress> findByOwner(Long ownerId) {
		if (ownerId == null) {
			return List.of();
		}
		var values = repository == null
					 ? new ArrayList<>(localTasks.values())
					 : repository.findAll()
		                         .stream()
		                         .map(this::fromEntity)
		                         .toList();
		return values.stream()
					 .filter(task -> ownerId.equals(task.getOwnerId()))
					 .sorted(Comparator.comparing(TaskProgress::getCreatedAt)
		                               .reversed())
					.toList();
	}

	public boolean remove(String taskId) {
		if (repository == null) {
			return localTasks.remove(taskId) != null;
		}
		if (!repository.existsById(taskId)) {
			return false;
		}
		repository.deleteById(taskId);
		localTasks.remove(taskId);
		return true;
	}

	public int size() {
		return repository == null ? localTasks.size() : Math.toIntExact(repository.count());
	}

	public int evictFinishedBefore(Instant cutoff) {
		if (repository == null) {
			var before = localTasks.size();
			localTasks.values()
			          .removeIf(task -> task.isFinished() && task.getFinishedAt() != null && task.getFinishedAt()
			                                                                                     .isBefore(cutoff));
			return before - localTasks.size();
		}
		return repository.deleteFinishedBefore(cutoff);
	}

	@Transactional(readOnly = false, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	public Optional<TaskProgress> claimNext(String workerId) {
		if (repository == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(repository.claimNext(workerId, leaseSeconds))
		               .map(entity -> {
						   var progress = fromEntity(entity);
						   progress.attach(this, entity.getPayload(), workerId);
						   var cached = localTasks.get(progress.getId());
						   if (cached != null) {
							   cached.updateFrom(progress);
							   progress = cached;
						   } else {
							   localTasks.put(progress.getId(), progress);
						   }
						   return progress;
					   });
	}

	public void heartbeat(TaskProgress progress) {
		if (repository != null && progress.getWorkerId() != null) {
			repository.heartbeat(progress.getId(), progress.getWorkerId(), leaseSeconds);
		}
	}

	void refreshCancellation(TaskProgress progress) {
		if (repository != null) {
			repository.findById(progress.getId())
			          .ifPresent(entity -> {
						  progress.cancelRequested.set(entity.isCancelRequested());
						  if (entity.getStatus() == TaskStatusEnum.CANCELLING) {
							  progress.status = TaskStatusEnum.CANCELLING;
						  }
					  });
		}
	}

	boolean requestCancel(TaskProgress progress) {
		if (repository == null) {
			return progress.requestCancelLocal();
		}
		var accepted = repository.requestCancel(progress.getId()) > 0;
		if (accepted) {
			progress.cancelRequested.set(true);
			if (progress.status == TaskStatusEnum.RUNNING) {
				progress.status = TaskStatusEnum.CANCELLING;
			}
		}
		return accepted;
	}

	/**
	 * Persists the current state of the task. The write commits on its own (see the class comment), so every reported step is
	 * visible to the clients immediately, also from inside a transactional task body.
	 */
	void save(TaskProgress progress) {
		if (repository == null) {
			localTasks.put(progress.getId(), progress);
			return;
		}

		inOwnTransaction(() -> repository.findById(progress.getId())
										 .ifPresent(entity -> {
											 if (progress.getWorkerId() == null || progress.getWorkerId()
																						   .equals(entity.getWorkerId())) {
												 copyToEntity(progress, entity);
												 repository.save(entity);
											 }
										 }));
	}

	void registerCompensation(TaskProgress progress, String description, String commandType, Object payload) {
		if (compensationRepository == null) {
			return;
		}
		var compensation = new TaskCompensationEntity();
		compensation.setTaskId(progress.getId());
		compensation.setDescription(description);
		compensation.setCommandType(commandType);
		compensation.setPayload(serialize(payload));
		compensation.setCreatedAt(Instant.now());
		// Committed on its own: the undo must survive even when the task body's transaction rolls back afterwards
		inOwnTransaction(() -> compensationRepository.save(compensation));
	}

	List<TaskCompensationEntity> durableCompensations(TaskProgress progress) {
		return compensationRepository == null ? List.of() : compensationRepository.findByTaskIdOrderByIdDesc(progress.getId());
	}

	void markCompensationDone(TaskCompensationEntity compensation) {
		if (compensationRepository != null) {
			compensation.setCompleted(true);
			compensationRepository.save(compensation);
		}
	}

	private TaskRecordEntity toEntity(TaskProgress progress) {
		var entity = new TaskRecordEntity();
		copyToEntity(progress, entity);
		entity.setPayload(progress.getPayload());
		return entity;
	}

	private void copyToEntity(TaskProgress progress, TaskRecordEntity entity) {
		entity.setId(progress.getId());
		entity.setOwnerId(progress.getOwnerId());
		entity.setType(progress.getType());
		entity.setTitle(progress.getTitle());
		entity.setStatus(progress.getStatus());
		entity.setTotalSteps(progress.getTotalSteps()
		                             .get());
		entity.setCompletedSteps(progress.getCompletedSteps()
		                                 .get());
		entity.setFailedSteps(progress.getFailedSteps()
		                              .get());
		entity.setRevertedSteps(progress.getRevertedSteps()
		                                .get());
		entity.setMessage(progress.getMessage());
		entity.setErrorMessage(progress.getErrorMessage());
		entity.setResult(progress.getResult() == null ? null : serialize(progress.getResult()));
		entity.setCreatedAt(progress.getCreatedAt());
		entity.setStartedAt(progress.getStartedAt());
		entity.setFinishedAt(progress.getFinishedAt());
		entity.setCancelRequested(progress.isCancelRequested());
		entity.setWorkerId(progress.getWorkerId());
		entity.setHeartbeatAt(Instant.now());
		if (progress.getStatus()
		            .isActive()) {
			entity.setLeaseUntil(Instant.now()
			                            .plusSeconds(leaseSeconds));
		}
	}

	private TaskProgress fromEntity(TaskRecordEntity entity) {
		var progress = new TaskProgress(entity.getId(), entity.getType(), entity.getTitle(), entity.getOwnerId(), entity.getTotalSteps());
		progress.totalSteps.set(entity.getTotalSteps());
		progress.completedSteps.set(entity.getCompletedSteps());
		progress.failedSteps.set(entity.getFailedSteps());
		progress.revertedSteps.set(entity.getRevertedSteps());
		progress.cancelRequested.set(entity.isCancelRequested());
		progress.status       = entity.getStatus();
		progress.message      = entity.getMessage();
		progress.errorMessage = entity.getErrorMessage();
		progress.result       = deserializeResult(entity.getResult());
		progress.startedAt    = entity.getStartedAt();
		progress.finishedAt   = entity.getFinishedAt();
		progress.createdAtOverride(entity.getCreatedAt());
		progress.attach(this, entity.getPayload(), entity.getWorkerId());
		return progress;
	}

	private String serialize(Object value) {
		if (value == null) {
			return "{}";
		}
		if (objectMapper == null) {
			return "{}";
		}
		return objectMapper.writeValueAsString(value);
	}

	private Object deserializeResult(String value) {
		if (value == null || objectMapper == null) {
			return null;
		}
		return objectMapper.readValue(value, Object.class);
	}

	@Scheduled(fixedDelayString = "${vempain.tasks.cleanup-interval-ms:60000}")
	public void evictExpired() {
		if (!schedulingEnabled) {
			return;
		}
		var evicted = evictFinishedBefore(Instant.now()
		                                         .minus(Duration.ofMinutes(retentionMinutes)));
		if (evicted > 0) {
			log.debug("Evicted {} finished background tasks", evicted);
		}
	}
}
