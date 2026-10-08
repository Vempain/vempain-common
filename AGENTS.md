# vempain-common — Agent Guide

## What this repo is

- `vempain-common` is a **shared Java library**, not a runnable service. Two Gradle modules are published to GitHub Packages:
  `api` (`fi.poltsi.vempain:vempain-common-api`) and `core` (`fi.poltsi.vempain:vempain-common-core`).
- `api` exists to break the dependency cycle between `vempain-admin-backend-api` and `vempain-file-backend-api`: every contract type that
  both backends need lives here, so the file backend can depend on the admin API (it calls the admin backend) without the admin API
  depending on the file API. Only plain contract types belong in `api`: DTOs, enums, value objects and the REST interface of the task
  facility. No Spring beans, Feign clients, JPA entities or business logic.
- `core` holds the shared Spring components: today the durable background task facility (`fi.poltsi.vempain.common.task`). It depends on
  `vempain-auth-core` (the task runner restores the task owner's security context from `UserAccountRepository`), never on a backend.

## Layout

| Path                                                               | Purpose                                                                       |
|--------------------------------------------------------------------|-------------------------------------------------------------------------------|
| `api/src/main/java/fi/poltsi/vempain/common/api/`                  | `FileTypeEnum`, `TaskStatusEnum`                                              |
| `api/src/main/java/fi/poltsi/vempain/common/api/request/`          | `LocationRequest`, `TagRequest`, `CopyrightRequest`                           |
| `api/src/main/java/fi/poltsi/vempain/common/api/response/`         | `LocationResponse`, `TaskAcceptedResponse`, `TaskProgressResponse`            |
| `api/src/main/java/fi/poltsi/vempain/common/rest/`                 | `TaskAPI` (`/tasks`: list, get, cancel, dismiss)                              |
| `core/src/main/java/fi/poltsi/vempain/common/task/`                | `TaskRunner`, `TaskProgressStore`, `TaskProgress`, `TaskController`, `TaskCommandExecutor` (interface), `TaskWork`, `Compensation`, `TaskCancelledException` |
| `core/src/main/java/fi/poltsi/vempain/common/task/entity/`         | `TaskRecordEntity`, `TaskCompensationEntity` (tables `task_record`, `task_compensation`) |
| `core/src/main/java/fi/poltsi/vempain/common/task/repository/`     | `TaskRecordRepository` (lease based `claimNext`), `TaskCompensationRepository` |
| `core/src/main/resources/db/task/task_tables.sql`                  | Reference schema; copied into each consumer's own Flyway tree                 |
| `*/src/test/java/...`                                              | `FileTypeEnumUTC`, `RequestContractJTC`, `ResponseContractJTC`, `TaskResponseContractJTC`, `TaskProgressStoreUTC`, `TaskProgressStoreDurableUTC`, `TaskRunnerUTC`, `TaskRunnerDurableUTC` |

## The task facility (core)

- A service starts work with `taskRunner.submitDurable(type, title, totalSteps, payload, localFallback)`: the task row is written to
  PostgreSQL (`task_record`, status QUEUED) and a worker of the hosting service claims it with a lease (`claimNext`, expired leases are
  re-claimed after a crash). The claimed task is executed through the host's single `TaskCommandExecutor` bean, which maps the task type
  and JSON payload to a service call; the `localFallback` lambda is only used by unit tests that build a `TaskRunner` without repositories
  (`new TaskRunner(new TaskProgressStore(), Runnable::run)`).
- Work reports progress through `TaskProgress` (`advance`, `advanceFailed`, `message`, `setTotalSteps`), calls `checkpoint()` between
  units of work so that a cancel request stops it, and registers undo actions with `registerCompensation` (in-process) or
  `registerDurableCompensation` (serialized command replayed by `TaskCommandExecutor.compensate`). On cancel or failure the runner runs
  the compensations most recent first and reports the reverted count.
- `TaskController` implements `TaskAPI`; tasks are private to their owner (`TaskRunner.currentUserId()` from the `UserDetailsImpl`
  principal of `vempain-auth`). Finished tasks are evicted after `vempain.tasks.retention-minutes`.
- Configuration keys of a host: `vempain.tasks.worker-count`, `poll-interval-ms`, `heartbeat-interval-ms`, `lease-seconds`,
  `retention-minutes`, `cleanup-interval-ms`, plus `vempain.scheduling.enabled` for the eviction job.
- Hosting checklist for a backend: depend on `vempain-common-core`; add `fi.poltsi.vempain.common` to component scanning,
  `fi.poltsi.vempain.common.task.repository` to `@EnableJpaRepositories` and `fi.poltsi.vempain.common.task.entity` to the entity scan of the
  datasource that holds the task tables; copy `db/task/task_tables.sql` into the host's Flyway tree with the host's next version; permit
  `/tasks/**` for authenticated users in the host `WebSecurityConfig`; provide exactly one `TaskCommandExecutor` bean and a host
  `TaskTypeEnum` documenting the task types and result payloads. The file and admin backends are the two reference hosts.
- The facility is type agnostic: task types are strings, payloads and results are JSON. Never add a backend specific type to `core`.

## Conventions

- JSON is mandatory snake_case: every DTO carries `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` from Jackson 3
  (`tools.jackson.databind`); never introduce camelCase JSON keys. The TypeScript models of `vempain-common-frontend` and the admin, file and
  website frontends mirror these names, and the backends' own contract tests pin them.
- Prefer Lombok annotations for boilerplate (`@Data`, `@Builder`/`@SuperBuilder`, constructors, `@Slf4j`); the `io.freefair.lombok` plugin
  is enabled. Every field carries an OpenAPI `@Schema` with description and example; request DTOs carry Jakarta Validation constraints.
- Text columns of the task tables are mapped with `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` (PostgreSQL `TEXT`), not `@Lob`, because the hosts
  validate the schema at startup.
- Adding or renaming a field is a contract change for two backends and three frontends: update the JTC here, release, bump
  `vempain-common` in the consumers' `gradle/libs.versions.toml`, and adjust their TypeScript models in the same change set.
- Java is tab-indented with a 160-character line limit (`.editorconfig`); do not mass-reformat.
- Test suffixes are shared with the other Vempain Java repos: `UTC` (unit), `JTC` (JSON contract); `ITC`/`CTC` are not expected here because
  there is no Spring context, the hosts cover the HTTP side with their own `TaskControllerCTC`/`TaskCTC`. Helper classes carry no suffix.
- Java, Spring Boot and Gradle versions in `gradle/libs.versions.toml` and the wrapper must stay identical to the consuming backends and
  `vempain-auth` (Java 25, Spring Boot 4.1.x, Gradle 9.8).

## Build, test and release

```bash
./gradlew clean test
./gradlew :api:publishToMavenLocal :core:publishToMavenLocal -PreleaseVersion=1.0.0   # local verification of a consumer before a release
```

- Run `./gradlew clean test` after every change and report the result. `core` needs GitHub Packages credentials for `vempain-auth-*`
  (`gpr.user`/`gpr.token` or `GITHUB_ACTOR`/`GITHUB_TOKEN`).
- CI (`.github/workflows/ci.yaml`) delegates to `Vempain/vempain-workflows/.github/workflows/spring-boot-library.yaml`: tests on pull requests,
  publish of both modules to GitHub Packages on `main` with the version derived from `VERSION` and the Git tags. Publishing needs the
  organisation secret `VEMPAIN_ACTION_TOKEN`.
- Release order after a contract change: `vempain-common` first, then `vempain-admin-backend` (its API artifact is consumed by the file backend),
  then `vempain-file-backend`.
- Dependabot (`.github/dependabot.yaml`) covers GitHub Actions and Gradle from Maven Central; no private registry is needed.

## Tag ACL rule

Tags are metadata, not ACL-bearing resources. Tag entities have no ACL information, so tag list, search, and mutation endpoints must not perform ACL checks on
tags. ACL checks apply only to resources that explicitly carry an ACL.
