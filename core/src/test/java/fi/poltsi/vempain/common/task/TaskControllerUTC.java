package fi.poltsi.vempain.common.task;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import fi.poltsi.vempain.auth.service.UserDetailsImpl;
import fi.poltsi.vempain.common.api.TaskStatusEnum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hosted {@code /tasks} controller: tasks are private to their owner, cancel and dismiss answer 409 when the task state does not allow them.
 */
@ExtendWith(MockitoExtension.class)
class TaskControllerUTC {

    @Mock
    private TaskProgressStore store;
    @Mock
    private TaskRunner runner;

    private TaskController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskController(store, runner);
        signIn(7L);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(long userId) {
        var principal = new UserDetailsImpl(userId, "user" + userId, "User", "user@nohost.nodomain", "pw", Set.of(), List.of());
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static TaskProgress task(String id, long ownerId, TaskStatusEnum status) {
        var progress = TaskProgress.unmanaged(id, "SCAN", "Scan", ownerId, 2, "{}");
        if (status == TaskStatusEnum.COMPLETED) {
            progress.start();
            progress.complete(null);
        }
        return progress;
    }

    @Test
    void listsTheOwnersTasks() {
        when(store.findByOwner(7L)).thenReturn(List.of(task("a", 7L, TaskStatusEnum.QUEUED)));

        var response = controller.getTasks();

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()
            .getFirst()
            .getTaskId()).isEqualTo("a");
    }

    @Test
    void returnsOwnTaskAndHidesOthersAsNotFound() {
        when(store.find("mine")).thenReturn(Optional.of(task("mine", 7L, TaskStatusEnum.QUEUED)));
        when(store.find("theirs")).thenReturn(Optional.of(task("theirs", 8L, TaskStatusEnum.QUEUED)));
        when(store.find("missing")).thenReturn(Optional.empty());

        assertThat(controller.getTask("mine")
            .getBody()
            .getTaskId()).isEqualTo("mine");
        assertThatThrownBy(() -> controller.getTask("theirs")).isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> controller.getTask("missing")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anonymousCallersSeeNoTask() {
        SecurityContextHolder.clearContext();
        when(store.find("mine")).thenReturn(Optional.of(task("mine", 7L, TaskStatusEnum.QUEUED)));

        assertThatThrownBy(() -> controller.getTask("mine")).isInstanceOf(ResponseStatusException.class);
        assertThat(controller.getTasks()
            .getBody()).isEmpty();
    }

    @Test
    void cancelAnswersAcceptedOrConflict() {
        var running = task("run", 7L, TaskStatusEnum.QUEUED);
        when(store.find("run")).thenReturn(Optional.of(running));
        when(runner.cancel(running)).thenReturn(true);

        assertThat(controller.cancelTask("run")
            .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        when(runner.cancel(running)).thenReturn(false);
        assertThatThrownBy(() -> controller.cancelTask("run")).isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void dismissRemovesFinishedTasksOnly() {
        when(store.find("done")).thenReturn(Optional.of(task("done", 7L, TaskStatusEnum.COMPLETED)));
        when(store.find("run")).thenReturn(Optional.of(task("run", 7L, TaskStatusEnum.QUEUED)));

        assertThat(controller.dismissTask("done")
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(store).remove("done");

        assertThatThrownBy(() -> controller.dismissTask("run")).isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        verify(store, never()).remove("run");
    }
}
