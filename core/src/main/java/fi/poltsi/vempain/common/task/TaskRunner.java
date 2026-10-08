package fi.poltsi.vempain.common.task;

import fi.poltsi.vempain.auth.repository.UserAccountRepository;
import fi.poltsi.vempain.auth.service.UserDetailsImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Submits durable commands and claims them with a PostgreSQL lease. The work itself is executed through the hosting service's
 * {@link TaskCommandExecutor}. The lambda overload of {@link #submit(String, String, long, TaskWork)} remains available for
 * focused unit tests and is never used by application services.
 */
@Slf4j
@Service
public class TaskRunner {
	private final TaskProgressStore store;
	private final Executor                  executor;
	private final boolean                   ownsExecutor;
	private final TaskCommandExecutor       commandExecutor;
	private final UserAccountRepository     userAccountRepository;
	private final String                    workerId = UUID.randomUUID()
	                                                       .toString();
	private final int                       workerCount;
	private final Map<String, TaskProgress> active   = new ConcurrentHashMap<>();

	@Autowired
	public TaskRunner(TaskProgressStore store, TaskCommandExecutor commandExecutor, UserAccountRepository userAccountRepository,
					  @Value("${vempain.tasks.worker-count:4}") int workerCount) {
		this(store, newExecutor(Math.max(1, workerCount)), true, commandExecutor, userAccountRepository, workerCount);
	}

	public TaskRunner(TaskProgressStore store, Executor executor) {
		this(store, executor, false, null, null, 1);
	}

	private TaskRunner(TaskProgressStore store, Executor executor, boolean ownsExecutor, TaskCommandExecutor commandExecutor,
					   UserAccountRepository userAccountRepository, int workerCount) {
		this.store                 = store;
		this.executor              = executor;
		this.ownsExecutor = ownsExecutor;
		this.commandExecutor       = commandExecutor;
		this.userAccountRepository = userAccountRepository;
		this.workerCount           = Math.max(1, workerCount);
	}

	public boolean cancel(TaskProgress progress) {
		var accepted = progress.requestCancel();
		if (accepted) {
			log.info("Cancellation requested for background task {} ({})", progress.getId(), progress.getType());
		}
		return accepted;
	}

	/**
	 * Creates a PostgreSQL-backed task. Payload must be JSON-serializable.
	 */
	public TaskProgress submit(String type, String title, long totalSteps, Object payload) {
		var progress = store.create(type, title, currentUserId(), totalSteps, payload);
		schedulePollingAfterCommit(progress);
		return progress;
	}

	/**
	 * Production uses the durable payload. The fallback exists solely for lightweight unit tests
	 * that construct a runner without a JPA repository.
	 */
	public TaskProgress submitDurable(String type, String title, long totalSteps, Object payload, TaskWork<?> localFallback) {
		return store.isDurable() ? submit(type, title, totalSteps, payload) : submit(type, title, totalSteps, localFallback);
	}

	/** Compatibility overload for existing unit tests; production code submits durable commands. */
	public TaskProgress submit(String type, String title, long totalSteps, TaskWork<?> work) {
		var progress = store.create(type, title, currentUserId(), totalSteps);
		var securityContext = copyOfCurrentSecurityContext();
		Runnable runnable = () -> {
			var previous = SecurityContextHolder.getContext();
			SecurityContextHolder.setContext(securityContext);
			try {
				executeLegacy(progress, work);
			} finally {
				SecurityContextHolder.setContext(previous);
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					executor.execute(runnable);
				}

				@Override
				public void afterCompletion(int status) {
					if (status != STATUS_COMMITTED) {
						progress.fail("The request was rolled back before the task could start");
					}
				}
			});
		} else {
			executor.execute(runnable);
		}
		return progress;
	}

	private void schedulePollingAfterCommit(TaskProgress progress) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					if (status == STATUS_COMMITTED) {
						executor.execute(TaskRunner.this::poll);
					} else {
						store.remove(progress.getId());
						progress.fail("The request was rolled back before the task could start");
					}
				}
			});
		} else {
			poll();
		}
	}

	@PostConstruct
	void start() {
		poll();
	}

	@Scheduled(fixedDelayString = "${vempain.tasks.poll-interval-ms:1000}")
	public void poll() {
		if (commandExecutor == null) {
			return;
		}
		for (int i = active.size(); i < workerCount; i++) {
			store.claimNext(workerId)
			     .ifPresent(progress -> {
					 active.put(progress.getId(), progress);
					 executor.execute(() -> executeDurable(progress));
				 });
		}
	}

	@Scheduled(fixedDelayString = "${vempain.tasks.heartbeat-interval-ms:10000}")
	void heartbeat() {
		// A task reports progress frequently, but heartbeat also protects long external calls.
		active.values()
		      .forEach(store::heartbeat);
	}

	private void executeDurable(TaskProgress progress) {
		var context = SecurityContextHolder.createEmptyContext();
		try {
			if (progress.getOwnerId() != null && userAccountRepository != null) {
				userAccountRepository.findById(progress.getOwnerId())
				                     .ifPresent(account ->
														context.setAuthentication(new UsernamePasswordAuthenticationToken(UserDetailsImpl.build(account), null,
																														  UserDetailsImpl.build(account)
						                                                                                                                 .getAuthorities())));
			}
			SecurityContextHolder.setContext(context);
			if (progress.isCancelRequested()) {
				progress.cancelled("Cancelled before it started");
				return;
			}
			progress.start();
			var result = commandExecutor.execute(progress);
			progress.complete(result);
		} catch (TaskCancelledException e) {
			var failures = revert(progress);
			progress.cancelled(cancelMessage(progress, failures));
		} catch (Exception e) {
			log.error("Durable task {} ({}) failed", progress.getId(), progress.getType(), e);
			var failures = revert(progress);
			var message  = e.getMessage() == null ? e.getClass()
			                                         .getSimpleName() : e.getMessage();
			progress.fail(failures == 0 ? message : message + " (" + failures + " changes could not be reverted)");
		} finally {
			active.remove(progress.getId());
			SecurityContextHolder.clearContext();
		}
	}

	private void executeLegacy(TaskProgress progress, TaskWork<?> work) {
		if (progress.isCancelRequested()) {
			progress.cancelled("Cancelled before it started");
			return;
		}
		progress.start();
		try {
			progress.complete(work.run(progress));
		} catch (TaskCancelledException e) {
			var failures = revert(progress);
			progress.cancelled(cancelMessage(progress, failures));
		} catch (Exception e) {
			var failures = revert(progress);
			var message  = e.getMessage() == null ? e.getClass()
			                                         .getSimpleName() : e.getMessage();
			progress.fail(failures == 0 ? message : message + " (" + failures + " changes could not be reverted)");
		}
	}

	private int revert(TaskProgress progress) {
		progress.reverting();
		var failures = 0;
		if (commandExecutor != null) {
			for (var compensation : store.durableCompensations(progress)) {
				if (compensation.isCompleted()) {
					continue;
				}
				progress.message("Reverting: " + compensation.getDescription());
				try {
					commandExecutor.compensate(compensation);
					store.markCompensationDone(compensation);
					progress.countReverted();
				} catch (Exception e) {
					failures++;
					log.error("Could not revert durable task compensation {}", compensation.getId(), e);
				}
			}
		}
		for (var registered : progress.drainCompensations()) {
			progress.message("Reverting: " + registered.description());
			try {
				registered.compensation().revert();
				progress.countReverted();
			} catch (Exception e) {
				failures++;
				log.error("Could not revert task {}", progress.getId(), e);
			}
		}
		return failures;
	}

	private static String cancelMessage(TaskProgress progress, int failures) {
		var reverted = progress.getRevertedSteps()
		                       .get();
		if (reverted == 0 && failures == 0) {
			return "Cancelled, nothing to revert";
		}
		var message = "Cancelled, reverted " + reverted + " change" + (reverted == 1 ? "" : "s");
		return failures == 0 ? message : message + ", " + failures + " could not be reverted";
	}

	public static Long currentUserId() {
		var authentication = SecurityContextHolder.getContext()
		                                          .getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl user ? user.getId() : null;
	}

	private static SecurityContext copyOfCurrentSecurityContext() {
		var copy = SecurityContextHolder.createEmptyContext();
		copy.setAuthentication(SecurityContextHolder.getContext()
		                                            .getAuthentication());
		return copy;
	}

	private static ThreadPoolTaskExecutor newExecutor(int workerCount) {
		var executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(workerCount);
		executor.setMaxPoolSize(workerCount);
		executor.setQueueCapacity(1000);
		executor.setThreadNamePrefix("task-runner-");
		executor.initialize();
		return executor;
	}

	@PreDestroy
	void shutdown() {
		if (ownsExecutor && executor instanceof ThreadPoolTaskExecutor pool) {
			pool.shutdown();
		}
	}
}
