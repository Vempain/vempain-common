package fi.poltsi.vempain.common.task;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.PersistenceException;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import fi.poltsi.vempain.auth.repository.UserAccountRepository;
import fi.poltsi.vempain.auth.service.UserDetailsImpl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;

/**
 * Submits durable commands and claims them with a PostgreSQL lease. The work itself is executed through the hosting service's {@link TaskCommandExecutor}. The
 * lambda overload of {@link #submit(String, String, long, TaskWork)} remains available for focused unit tests and is never used by application services.
 */
@Slf4j
@Service
public class TaskRunner {

    private final TaskProgressStore store;
    private final Executor executor;
    private final boolean ownsExecutor;
    private final TaskCommandExecutor commandExecutor;
    private final UserAccountRepository userAccountRepository;
    private final String workerId = UUID.randomUUID()
        .toString();
    private final int workerCount;
    private final Map<String, TaskProgress> active = new ConcurrentHashMap<>();
    /**
     * What the user is told when a task failed in the database layer; the details are only logged.
     */
    static final String INTERNAL_ERROR_MESSAGE = "The task failed because of an internal error, the details are in the server log";
    private final TaskMetrics metrics;
    /**
     * Tracer of the host, when it traces: a task runs later on another thread, so it gets a span of its own ({@code task <type>}); null without tracing.
     */
    private final Tracer tracer;

    @Autowired
    public TaskRunner(TaskProgressStore store, TaskCommandExecutor commandExecutor, UserAccountRepository userAccountRepository,
            @Value("${vempain.tasks.worker-count:4}") int workerCount, ObjectProvider<MeterRegistry> meterRegistry, ObjectProvider<Tracer> tracer) {
        this(store, newExecutor(Math.max(1, workerCount)), true, commandExecutor, userAccountRepository, workerCount,
                meterRegistry.getIfAvailable(() -> Metrics.globalRegistry), tracer.getIfAvailable());
    }

    /**
     * Production constructor without a Spring context; the meters go to the global registry, which is empty (and therefore free) outside Spring Boot.
     */
    public TaskRunner(TaskProgressStore store, TaskCommandExecutor commandExecutor, UserAccountRepository userAccountRepository, int workerCount) {
        this(store, newExecutor(Math.max(1, workerCount)), true, commandExecutor, userAccountRepository, workerCount, Metrics.globalRegistry, null);
    }

    public TaskRunner(TaskProgressStore store, Executor executor) {
        this(store, executor, Metrics.globalRegistry);
    }

    /**
     * Runner for unit tests that want to assert the task meters: the given registry receives them.
     */
    public TaskRunner(TaskProgressStore store, Executor executor, MeterRegistry meterRegistry) {
        this(store, executor, false, null, null, 1, meterRegistry, null);
    }

    /**
     * Runner for unit tests of the tracing behaviour: the given tracer receives the task spans.
     */
    public TaskRunner(TaskProgressStore store, Executor executor, MeterRegistry meterRegistry, Tracer tracer) {
        this(store, executor, false, null, null, 1, meterRegistry, tracer);
    }

    private TaskRunner(TaskProgressStore store, Executor executor, boolean ownsExecutor, TaskCommandExecutor commandExecutor,
            UserAccountRepository userAccountRepository, int workerCount, MeterRegistry meterRegistry, Tracer tracer) {
        this.store = store;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.commandExecutor = commandExecutor;
        this.userAccountRepository = userAccountRepository;
        this.workerCount = Math.max(1, workerCount);
        this.metrics = new TaskMetrics(meterRegistry, active::size, store::countQueued);
        this.tracer = tracer;
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
        return submit(type, title, totalSteps, payload, null);
    }

    /**
     * @param lane the lane whose concurrency limit the task waits for, or null for a task that runs as soon as a worker is free
     */
    public TaskProgress submit(String type, String title, long totalSteps, Object payload, TaskLane lane) {
        var progress = lane == null ? store.create(type, title, currentUserId(), totalSteps, payload)
                : store.create(type, title, currentUserId(), totalSteps, payload, lane);
        metrics.submitted(type, true);
        // Logged inside the trace of the submitting request: the task itself runs later in a trace of its own, the task id joins the two
        log.info("Submitted background task {} ({}): {}", progress.getId(), type, title);
        schedulePollingAfterCommit(progress);
        return progress;
    }

    /** Compatibility overload for existing unit tests; production code submits durable commands. */
    public TaskProgress submit(String type, String title, long totalSteps, TaskWork<?> work) {
        var progress = store.create(type, title, currentUserId(), totalSteps);
        metrics.submitted(type, false);
        log.info("Submitted background task {} ({}): {}", progress.getId(), type, title);
        var securityContext = copyOfCurrentSecurityContext();
        Runnable runnable = () -> {
            var previous = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(securityContext);
            try {
                traced(progress, () -> executeLegacy(progress, work));
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

    /**
     * Production uses the durable payload. The fallback exists solely for lightweight unit tests that construct a runner without a JPA repository.
     */
    public TaskProgress submitDurable(String type, String title, long totalSteps, Object payload, TaskWork<?> localFallback) {
        return submitDurable(type, title, totalSteps, payload, null, localFallback);
    }

    /**
     * @param lane the lane whose concurrency limit the durable task waits for (see {@link TaskLane}), or null; the local fallback ignores it
     */
    public TaskProgress submitDurable(String type, String title, long totalSteps, Object payload, TaskLane lane, TaskWork<?> localFallback) {
        return store.isDurable() ? submit(type, title, totalSteps, payload, lane) : submit(type, title, totalSteps, localFallback);
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
                    executor.execute(() -> traced(progress, () -> executeDurable(progress)));
                });
        }
    }

    @Scheduled(fixedDelayString = "${vempain.tasks.heartbeat-interval-ms:10000}")
    void heartbeat() {
        // A task reports progress frequently, but heartbeat also protects long external calls.
        active.values()
            .forEach(store::heartbeat);
    }

    /**
     * Runs the task body with the task id in the MDC of every log line it writes and, when the host traces, inside a span of its own named after the task type
     * (a task executes later on a worker thread, outside the trace of the request that submitted it).
     */
    private void traced(TaskProgress progress, Runnable body) {
        MDC.put("taskId", progress.getId());
        MDC.put("taskType", progress.getType());
        try {
            if (tracer == null) {
                body.run();
                return;
            }
            var span = tracer.nextSpan()
                .name("task " + progress.getType())
                .tag("task.type", progress.getType() == null ? "" : progress.getType())
                .tag("task.id", progress.getId())
                .start();
            try (var scope = tracer.withSpan(span)) {
                body.run();
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        } finally {
            MDC.remove("taskId");
            MDC.remove("taskType");
        }
    }

    private void executeDurable(TaskProgress progress) {
        var context = SecurityContextHolder.createEmptyContext();
        try {
            if (progress.getOwnerId() != null
                && userAccountRepository != null) {
                userAccountRepository.findById(progress.getOwnerId())
                    .ifPresent(account -> context
                        .setAuthentication(new UsernamePasswordAuthenticationToken(UserDetailsImpl.build(account), null, UserDetailsImpl.build(account)
                            .getAuthorities())));
            }
            SecurityContextHolder.setContext(context);
            if (progress.isCancelRequested()) {
                progress.cancelled("Cancelled before it started");
                return;
            }
            progress.start();
            metrics.started(progress);
            var result = commandExecutor.execute(progress);
            progress.complete(result);
        } catch (TaskCancelledException e) {
            var failures = revert(progress);
            progress.cancelled(cancelMessage(progress, failures));
        } catch (Exception e) {
            log.error("Durable task {} ({}) failed", progress.getId(), progress.getType(), e);
            var failures = revert(progress);
            var message = failureMessage(e);
            progress.fail(failures == 0 ? message : message + " (" + failures + " changes could not be reverted)");
        } finally {
            active.remove(progress.getId());
            metrics.finished(progress);
            SecurityContextHolder.clearContext();
        }
    }

    private void executeLegacy(TaskProgress progress, TaskWork<?> work) {
        if (progress.isCancelRequested()) {
            progress.cancelled("Cancelled before it started");
            metrics.finished(progress);
            return;
        }
        progress.start();
        metrics.started(progress);
        try {
            progress.complete(work.run(progress));
        } catch (TaskCancelledException e) {
            var failures = revert(progress);
            progress.cancelled(cancelMessage(progress, failures));
        } catch (Exception e) {
            log.error("Task {} ({}) failed", progress.getId(), progress.getType(), e);
            var failures = revert(progress);
            var message = failureMessage(e);
            progress.fail(failures == 0 ? message : message + " (" + failures + " changes could not be reverted)");
        } finally {
            metrics.finished(progress);
        }
    }

    /**
     * The failure text shown to the user who follows the task. A database, persistence or transaction failure anywhere in the cause chain is reported as an
     * internal error only: the driver's message names tables, constraints and statements, which belong in the server log (written by the caller), not in the
     * UI. Other failures carry the message the service raised them with (the reason of a {@link ResponseStatusException}), or the exception's name when it has
     * none.
     */
    static String failureMessage(Exception e) {
        if (isDataAccessFailure(e)) {
            return INTERNAL_ERROR_MESSAGE;
        }

        if (e instanceof ResponseStatusException status
            && status.getReason() != null) {
            return status.getReason();
        }

        return e.getMessage() == null ? e.getClass()
            .getSimpleName() : e.getMessage();
    }

    private static boolean isDataAccessFailure(Throwable throwable) {
        var seen = 0;
        for (var cause = throwable; cause != null
                                    && seen < 32; cause = cause.getCause() == cause ? null : cause.getCause(), seen++) {
            if (cause instanceof DataAccessException
                || cause instanceof TransactionException
                || cause instanceof PersistenceException
                || cause instanceof SQLException) {
                return true;
            }
        }
        return false;
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
                    metrics.compensationFailed(progress);
                    log.error("Could not revert durable task compensation {}", compensation.getId(), e);
                }
            }
        }
        for (var registered : progress.drainCompensations()) {
            progress.message("Reverting: " + registered.description());
            try {
                registered.compensation()
                    .revert();
                progress.countReverted();
            } catch (Exception e) {
                failures++;
                metrics.compensationFailed(progress);
                log.error("Could not revert task {}", progress.getId(), e);
            }
        }
        return failures;
    }

    private static String cancelMessage(TaskProgress progress, int failures) {
        var reverted = progress.getRevertedSteps()
            .get();
        if (reverted == 0
            && failures == 0) {
            return "Cancelled, nothing to revert";
        }
        var message = "Cancelled, reverted " + reverted + " change" + (reverted == 1 ? "" : "s");
        return failures == 0 ? message : message + ", " + failures + " could not be reverted";
    }

    public static Long currentUserId() {
        var authentication = SecurityContextHolder.getContext()
            .getAuthentication();
        return authentication != null
               && authentication.getPrincipal() instanceof UserDetailsImpl user ? user.getId() : null;
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
        if (ownsExecutor
            && executor instanceof ThreadPoolTaskExecutor pool) {
            pool.shutdown();
        }
    }
}
