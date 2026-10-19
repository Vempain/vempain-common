package fi.poltsi.vempain.common.task;

import org.junit.jupiter.api.Test;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task runner reports its meters: submissions, durations by final status, failed and reverted steps, failed undo actions and the active/queued gauges.
 */
class TaskMetricsUTC {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TaskProgressStore store = new TaskProgressStore();
    private final TaskRunner runner = new TaskRunner(store, Runnable::run, registry);

    @Test
    void completedWorkCountsTheSubmissionAndTimesItWithItsStatus() {
        var progress = runner.submit("SCAN", "Scan", 2, p -> {
            p.advance("one");
            p.advance("two");
            return "ok";
        });

        assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
        assertThat(registry.get(TaskMetrics.SUBMITTED)
            .tags("type", "SCAN", "durable", "false")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(registry.get(TaskMetrics.DURATION)
            .tags("type", "SCAN", "status", "COMPLETED")
            .timer()
            .count()).isEqualTo(1);
        assertThat(registry.find(TaskMetrics.STEPS_FAILED)
            .counter()).as("no failed steps, no counter")
            .isNull();
    }

    @Test
    void failedWorkRecordsItsFailedStepsRevertedChangesAndBrokenUndoActions() {
        var progress = runner.submit("PUBLISH", "Publish", 3, p -> {
            p.advance("sent");
            p.registerCompensation("remove sent", () -> {});
            p.registerCompensation("broken undo", () -> {
                throw new IllegalStateException("cannot undo");
            });
            p.advanceFailed("second failed");
            throw new IllegalStateException("admin refused");
        });

        assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
        assertThat(registry.get(TaskMetrics.DURATION)
            .tags("type", "PUBLISH", "status", "FAILED")
            .timer()
            .count()).isEqualTo(1);
        assertThat(registry.get(TaskMetrics.STEPS_FAILED)
            .tags("type", "PUBLISH")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(registry.get(TaskMetrics.STEPS_REVERTED)
            .tags("type", "PUBLISH")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(registry.get(TaskMetrics.COMPENSATIONS_FAILED)
            .tags("type", "PUBLISH")
            .counter()
            .count()).isEqualTo(1.0);
    }

    @Test
    void cancelledBeforeStartIsNotTimedButStillFinishes() {
        var queued = new java.util.ArrayList<Runnable>();
        var lazyRunner = new TaskRunner(store, queued::add, registry);
        var progress = lazyRunner.submit("SCAN", "Scan", 1, p -> "never");
        lazyRunner.cancel(progress);
        queued.getFirst()
            .run();

        assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.CANCELLED);
        assertThat(registry.find(TaskMetrics.DURATION)
            .timer()).as("work that never started has no duration")
            .isNull();
    }

    @Test
    void gaugesFollowTheRunnerAndTheStore() {
        assertThat(registry.get(TaskMetrics.ACTIVE)
            .gauge()
            .value()).isZero();
        assertThat(registry.get(TaskMetrics.QUEUED)
            .gauge()
            .value()).isZero();

        var queued = new java.util.ArrayList<Runnable>();
        var lazyRunner = new TaskRunner(store, queued::add, registry);
        lazyRunner.submit("SCAN", "Scan", 1, p -> "later");

        assertThat(store.countQueued()).isEqualTo(1);
        assertThat(registry.get(TaskMetrics.QUEUED)
            .gauge()
            .value()).as("the in-memory store counts its queued tasks")
            .isEqualTo(1.0);
    }

    @Test
    void blankTypesAreReportedAsUnknown() {
        runner.submit("", "No type", 1, p -> "ok");

        assertThat(registry.get(TaskMetrics.SUBMITTED)
            .tags("type", "unknown")
            .counter()
            .count()).isEqualTo(1.0);
    }
}
