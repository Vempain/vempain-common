package fi.poltsi.vempain.common.task;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A task body runs in a span of its own named after the task type, with the task id in the MDC, and without a tracer only the MDC is set.
 */
class TaskRunnerTracingUTC {

    private final TaskProgressStore store = new TaskProgressStore();

    @Test
    void taskRunsInItsOwnSpanWithTheTaskIdInTheMdc() {
        var tracer = new SimpleTracer();
        var runner = new TaskRunner(store, Runnable::run, new SimpleMeterRegistry(), tracer);
        var seenTaskId = new AtomicReference<String>();
        var seenType = new AtomicReference<String>();

        var progress = runner.submit("SCAN", "Scan", 1, p -> {
            seenTaskId.set(MDC.get("taskId"));
            seenType.set(MDC.get("taskType"));
            p.advance("done");
            return "ok";
        });

        assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
        assertThat(seenTaskId.get()).isEqualTo(progress.getId());
        assertThat(seenType.get()).isEqualTo("SCAN");
        assertThat(MDC.get("taskId")).as("the MDC is cleaned up after the task")
            .isNull();
        var span = tracer.onlySpan();
        assertThat(span.getName()).isEqualTo("task SCAN");
        assertThat(span.getTags()).containsEntry("task.type", "SCAN")
            .containsEntry("task.id", progress.getId());
        assertThat(span.getEndTimestamp()).as("the span ended")
            .isAfter(java.time.Instant.EPOCH);
    }

    @Test
    void failingTaskEndsItsSpanAndTheRunnerSurvives() {
        var tracer = new SimpleTracer();
        var runner = new TaskRunner(store, Runnable::run, new SimpleMeterRegistry(), tracer);

        var progress = runner.submit("PUBLISH", "Publish", 1, p -> {
            throw new IllegalStateException("admin refused");
        });

        assertThat(progress.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
        assertThat(tracer.onlySpan()
            .getEndTimestamp()).isAfter(java.time.Instant.EPOCH);
        assertThat(MDC.get("taskId")).isNull();
    }

    @Test
    void withoutATracerOnlyTheMdcIsSet() {
        var runner = new TaskRunner(store, Runnable::run, new SimpleMeterRegistry());
        var seenTaskId = new AtomicReference<String>();

        var progress = runner.submit("SCAN", "Scan", 1, p -> {
            seenTaskId.set(MDC.get("taskId"));
            return "ok";
        });

        assertThat(seenTaskId.get()).isEqualTo(progress.getId());
        assertThat(MDC.get("taskId")).isNull();
    }
}
