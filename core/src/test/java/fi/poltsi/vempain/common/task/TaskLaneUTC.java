package fi.poltsi.vempain.common.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskLaneUTC {

    @Test
    void aLaneHasANameAndALimitOfAtLeastOne() {
        var lane = TaskLane.of("site-sftp", 2);

        assertThat(lane.name()).isEqualTo("site-sftp");
        assertThat(lane.limit()).isEqualTo(2);
        assertThat(TaskLane.of("x", 1)).isEqualTo(new TaskLane("x", 1));
    }

    @Test
    void invalidLanesAreRejected() {
        assertThatThrownBy(() -> TaskLane.of(null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskLane.of(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskLane.of("l".repeat(TaskLane.MAX_NAME_LENGTH + 1), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskLane.of("site-sftp", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
