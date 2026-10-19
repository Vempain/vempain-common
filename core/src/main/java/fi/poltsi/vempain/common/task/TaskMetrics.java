package fi.poltsi.vempain.common.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The meters of the background task facility, shared by every backend that hosts a {@link TaskRunner}:
 * <ul>
 * <li>{@code vempain.tasks.submitted} counter, tags {@code type} and {@code durable};</li>
 * <li>{@code vempain.tasks.duration} timer from the start of the work to its end, tags {@code type} and the final {@code status};</li>
 * <li>{@code vempain.tasks.active} gauge of the tasks this worker is executing and {@code vempain.tasks.queued} gauge of the tasks waiting for one;</li>
 * <li>{@code vempain.tasks.steps.failed} and {@code vempain.tasks.steps.reverted} counters by {@code type}, taken from the finished task;</li>
 * <li>{@code vempain.tasks.compensations.failed} counter by {@code type}: an undo action that threw while cancelling or failing.</li>
 * </ul>
 * Tags are the task type and status only, never ids or titles, so the series stay bounded.
 */
public class TaskMetrics {

    static final String SUBMITTED = "vempain.tasks.submitted";
    static final String DURATION = "vempain.tasks.duration";
    static final String ACTIVE = "vempain.tasks.active";
    static final String QUEUED = "vempain.tasks.queued";
    static final String STEPS_FAILED = "vempain.tasks.steps.failed";
    static final String STEPS_REVERTED = "vempain.tasks.steps.reverted";
    static final String COMPENSATIONS_FAILED = "vempain.tasks.compensations.failed";

    private final MeterRegistry registry;
    private final Map<String, Timer.Sample> running = new ConcurrentHashMap<>();

    /**
     * @param active number of tasks this runner is executing right now
     * @param queued number of tasks waiting for a worker
     */
    public TaskMetrics(MeterRegistry registry, Supplier<Number> active, Supplier<Number> queued) {
        this.registry = registry;
        Gauge.builder(ACTIVE, active::get)
            .description("Background tasks this worker is executing")
            .register(registry);
        Gauge.builder(QUEUED, queued::get)
            .description("Background tasks waiting for a worker")
            .register(registry);
    }

    public void submitted(String type, boolean durable) {
        Counter.builder(SUBMITTED)
            .description("Background tasks submitted")
            .tag("type", tag(type))
            .tag("durable", Boolean.toString(durable))
            .register(registry)
            .increment();
    }

    public void started(TaskProgress progress) {
        running.put(progress.getId(), Timer.start(registry));
    }

    /**
     * Records the duration with the final status and the step counters of the task; a task that never started (cancelled before it ran) only counts its steps.
     */
    public void finished(TaskProgress progress) {
        var type = tag(progress.getType());
        var sample = running.remove(progress.getId());
        if (sample != null) {
            sample.stop(Timer.builder(DURATION)
                .description("Duration of a background task from start to end")
                .tag("type", type)
                .tag("status", tag(progress.getStatus() == null ? null
                        : progress.getStatus()
                            .name()))
                .register(registry));
        }
        increment(STEPS_FAILED, "Steps of background tasks that failed", type, progress.getFailedSteps()
            .get());
        increment(STEPS_REVERTED, "Changes of background tasks that were reverted", type, progress.getRevertedSteps()
            .get());
    }

    public void compensationFailed(TaskProgress progress) {
        increment(COMPENSATIONS_FAILED, "Undo actions of background tasks that failed", tag(progress.getType()), 1);
    }

    private void increment(String name, String description, String type, long amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder(name)
            .description(description)
            .tag("type", type)
            .register(registry)
            .increment(amount);
    }

    private static String tag(String value) {
        return value == null
               || value.isBlank() ? "unknown" : value;
    }
}
