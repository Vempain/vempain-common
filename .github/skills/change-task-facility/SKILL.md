---
name: change-task-facility
description: Change the durable background task facility in vempain-common core (TaskRunner, TaskProgress, TaskProgressStore, TaskController, compensations, cancellation) without breaking the backends that host it. Use for any task runtime change.
---

# The task facility in vempain-common

- `core/src/main/java/fi/poltsi/vempain/common/task/`: `TaskRunner` (submit, claim with a PostgreSQL lease, worker pool, heartbeat,
  revert), `TaskProgress` (steps, messages, compensations, checkpoint), `TaskProgressStore` (`task_record`/`task_compensation` rows,
  in-memory map only for tests), `TaskController` (`TaskAPI` at `/api/tasks`), `TaskCommandExecutor` (implemented by each host),
  `Compensation`, `TaskCancelledException`.
- Hosts: `vempain-admin-backend` (`AdminTaskCommandExecutor`) and `vempain-file-backend` (`FileTaskCommandExecutor`); they scan
  `fi.poltsi.vempain.common.task.{entity,repository}` and carry the task tables in their own Flyway trees (reference schema here). A schema
  change needs a migration in both hosts.
- Invariants to keep:
    - the facility is type agnostic (task types are strings, payloads JSON, results `Object`);
    - progress writes and durable compensations commit in their own `REQUIRES_NEW` transaction on the primary `PlatformTransactionManager`
      (injected through `ObjectProvider`, the admin backend has two managers), so steps are visible while a transactional task body runs;
    - totals are set at submit, `complete()` tops `completedSteps` up to the total, `percent()` is 100 only when finished;
    - cancellation is cooperative: `checkpoint()` throws `TaskCancelledException`, the runner runs compensations most recent first, durable
      ones through `TaskCommandExecutor.compensate`;
    - the owner's security context is restored on the worker thread from `UserAccountRepository`;
    - tasks are private to their owner in `TaskController`.
- Unit test constructors must keep working: `new TaskProgressStore()` (in-memory), `new TaskRunner(store, Runnable::run)` (synchronous),
  `TaskProgress.unmanaged(...)`; the backends' unit tests rely on them.
- Tests here: `TaskProgressStoreUTC`, `TaskProgressStoreDurableUTC` (mocked repositories and transaction manager), `TaskRunnerUTC`,
  `TaskRunnerDurableUTC`, `TaskResponseContractJTC`. Add cases for every new rule; run `./gradlew clean test`.
- Frontend half: `vempain-common-frontend` (`TaskAPI`, `TaskProgressProvider`, tray); keep the JSON contract (`TaskProgressResponse`)
  identical and release it after this library.
