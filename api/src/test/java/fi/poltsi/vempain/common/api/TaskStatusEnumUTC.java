package fi.poltsi.vempain.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusEnumUTC {

    @Test
    void everyStatusIsEitherActiveOrFinished() {
        for (var status : TaskStatusEnum.values()) {
            assertThat(status.isActive()).as(status.name())
                .isNotEqualTo(status.isFinished());
        }
        assertThat(TaskStatusEnum.QUEUED.isActive()).isTrue();
        assertThat(TaskStatusEnum.CANCELLING.isActive()).isTrue();
        assertThat(TaskStatusEnum.CANCELLED.isFinished()).isTrue();
        assertThat(TaskStatusEnum.FAILED.isFinished()).isTrue();
    }
}
