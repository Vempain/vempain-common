package fi.poltsi.vempain.common.task;

/**
 * A lane limits how many tasks run at the same time: tasks submitted into the same lane run at most {@link #limit()} at a time, across every worker and every
 * instance of the hosting service, and the rest wait in submit order while unrelated tasks keep using the other workers. The hosting service uses a lane for
 * work that competes for one resource, such as the SFTP sessions towards the site server. The limit is stored with the task when it is submitted, so a changed
 * configuration applies to the tasks submitted after the change.
 *
 * @param name  the lane, at most {@value #MAX_NAME_LENGTH} characters
 * @param limit how many tasks of the lane may run at the same time, at least 1
 */
public record TaskLane(String name, int limit) {

    public static final int MAX_NAME_LENGTH = 80;

    public TaskLane {
        if (name == null
            || name.isBlank()) {
            throw new IllegalArgumentException("A task lane needs a name");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A task lane name is at most " + MAX_NAME_LENGTH + " characters: " + name);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("A task lane runs at least one task at a time: " + limit);
        }
    }

    public static TaskLane of(String name, int limit) {
        return new TaskLane(name, limit);
    }
}
