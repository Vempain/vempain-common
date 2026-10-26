# vempain-common — Agent Guide

## What this repo is

- `vempain-common` is a **shared Java library**, not a runnable service. Two Gradle modules are published to GitHub Packages:
  `api` (`fi.poltsi.vempain:vempain-common-api`) and `core` (`fi.poltsi.vempain:vempain-common-core`).
- `api` exists to break the dependency cycle between `vempain-admin-backend-api` and `vempain-file-backend-api`: every contract type that
  both backends need lives here, so the file backend can depend on the admin API (it calls the admin backend) without the admin API
  depending on the file API. Only plain contract types belong in `api`: DTOs, enums, value objects and the REST interface of the task
  facility. No Spring beans, Feign clients, JPA entities or business logic.
- `core` holds the shared Spring components: the durable background task facility (`fi.poltsi.vempain.common.task`) and the shared ExifTool
  runner with its JSON parsers (`fi.poltsi.vempain.common.metadata`). It depends on `vempain-auth-core` (the task runner restores the task
  owner's security context from `UserAccountRepository`), never on a backend.

## Layout

|                              Path                              |                                                                                                   Purpose                                                                                                   |
|----------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `api/src/main/java/fi/poltsi/vempain/common/api/`              | `FileTypeEnum`, `TaskStatusEnum`                                                                                                                                                                            |
| `api/src/main/java/fi/poltsi/vempain/common/api/request/`      | `LocationRequest`, `TagRequest`, `CopyrightRequest`                                                                                                                                                         |
| `api/src/main/java/fi/poltsi/vempain/common/api/response/`     | `LocationResponse`, `TaskAcceptedResponse`, `TaskProgressResponse`                                                                                                                                          |
| `api/src/main/java/fi/poltsi/vempain/common/rest/`             | `TaskAPI` (`/tasks`: list, get, cancel, dismiss)                                                                                                                                                            |
| `core/src/main/java/fi/poltsi/vempain/common/task/`            | `TaskRunner`, `TaskProgressStore`, `TaskProgress`, `TaskLane`, `TaskController`, `TaskCommandExecutor` (interface), `TaskWork`, `Compensation`, `TaskCancelledException`                                    |
| `core/src/main/java/fi/poltsi/vempain/common/task/`            | `TaskMetrics` (the `vempain.tasks.*` meters)                                                                                                                                                                |
| `core/src/main/java/fi/poltsi/vempain/common/metrics/`         | `MetricsExportEnvironmentPostProcessor` (`vempain.metrics.*` -> OTLP push export; `META-INF/spring.factories`)                                                                                              |
| `core/src/main/java/fi/poltsi/vempain/common/metadata/`        | `ExifToolService` (pooled `-stay_open` exiftool), `ExifToolProperties`, `StayOpenExifToolStrategy`, `MergedOutputCommandExecutor`, `MetadataJson` (parsers), `GpsData`, `MetadataEntry`, `RightsAndCreator` |
| `core/src/main/java/fi/poltsi/vempain/common/task/entity/`     | `TaskRecordEntity`, `TaskCompensationEntity` (tables `task_record`, `task_compensation`)                                                                                                                    |
| `core/src/main/java/fi/poltsi/vempain/common/task/repository/` | `TaskRecordRepository` (lease based `claimNext`), `TaskCompensationRepository`                                                                                                                              |
| `core/src/main/resources/db/task/task_tables.sql`              | Reference schema; copied into each consumer's own Flyway tree (`task_lanes.sql`: the incremental lane columns for hosts created before them)                                                                |
| `*/src/test/java/...`                                          | `FileTypeEnumUTC`, `RequestContractJTC`, `ResponseContractJTC`, `TaskResponseContractJTC`, `TaskProgressStoreUTC`, `TaskProgressStoreDurableUTC`, `TaskRunnerUTC`, `TaskRunnerDurableUTC`                   |

## The task facility (core)

- A service starts work with `taskRunner.submitDurable(type, title, totalSteps, payload, localFallback)`: the task row is written to
  PostgreSQL (`task_record`, status QUEUED) and a worker of the hosting service claims it with a lease (`claimNext`, expired leases are
  re-claimed after a crash). The claimed task is executed through the host's single `TaskCommandExecutor` bean, which maps the task type
  and JSON payload to a service call; the `localFallback` lambda is only used by unit tests that build a `TaskRunner` without repositories
  (`new TaskRunner(new TaskProgressStore(), Runnable::run)`).
- Lanes (`TaskLane`): `submitDurable(type, title, totalSteps, payload, TaskLane.of(name, limit), localFallback)` stores the lane and its
  limit with the task (`task_record.lane`, `lane_limit`). `claimNext` only claims a lane task while fewer than `lane_limit` tasks of that
  lane hold a live lease, counted inside the claim query and serialized with a transaction level advisory lock (`TaskRecordRepository.lockClaims`,
  `CLAIM_LOCK_KEY`), so the limit holds across every worker and instance of the host. Queued tasks of a
  full lane do not block tasks of other lanes or without a lane. The admin backend puts every task that opens an SFTP session to the site
  server (page and gallery publishing) into the `site-sftp` lane with the configured `vempain.site.sftp-concurrency`; the limit stored with a
  task is the one configured when it was submitted. The lane is not part of the `TaskProgressResponse`.
- `TaskProgressStore` writes every progress report (`advance`, `advanceFailed`, `message`, `setTotalSteps`, status changes) and every
  durable compensation in a transaction of its own (`REQUIRES_NEW` on the primary `PlatformTransactionManager`, injected through an
  `ObjectProvider` so that a dual-datasource host such as the admin backend uses its task datasource). Task bodies run inside the
  hosting service's transactions; without this the steps would only become visible to the polling clients when the body commits.
  Report one step per smallest unit of work (every transferred file, not the gallery or group containing them) and set the total at
  submit time so that `total_steps` is right from the first poll (`TaskProgressStoreDurableUTC`).
- Work reports progress through `TaskProgress` (`advance`, `advanceFailed`, `message`, `setTotalSteps`), calls `checkpoint()` between
  units of work so that a cancel request stops it, and registers undo actions with `registerCompensation` (in-process) or
  `registerDurableCompensation` (serialized command replayed by `TaskCommandExecutor.compensate`). On cancel or failure the runner runs
  the compensations most recent first and reports the reverted count.
- Every progress snapshot (`TaskProgress.toResponse()`) carries `estimated_remaining_seconds`: the average pace of the completed steps
  extrapolated over the remaining ones (`elapsed * remaining / completed`, rounded up; `TaskProgress.estimatedRemainingSeconds`). It is null
  while the task is queued, before its first completed step, without a known step count, while cancelling and once finished; the tray of
  `vempain-common-frontend` shows it next to the step count. Keep the estimate this simple: it is derived on every poll, nothing is stored.
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
- `TaskMetrics` (created by `TaskRunner` from the host's `MeterRegistry`, or the global registry outside Spring) reports
  `vempain.tasks.submitted{type,durable}`, `vempain.tasks.duration{type,status}`, the `vempain.tasks.active` and `vempain.tasks.queued`
  gauges (`TaskProgressStore.countQueued`), `vempain.tasks.steps.failed{type}`, `vempain.tasks.steps.reverted{type}` and
  `vempain.tasks.compensations.failed{type}`. Tags are the task type and status only. Unit tests that assert meters build the runner with
  `new TaskRunner(store, Runnable::run, new SimpleMeterRegistry())` (`TaskMetricsUTC`).
- A task body runs with `taskId`/`taskType` in the MDC and, when the host traces (Micrometer `Tracer` bean present), in a span of its
  own named `task <type>` with tags `task.type`/`task.id` (`TaskRunner.traced`); the submit logs the task id inside the request's
  trace, which is how a request and the task it started are joined in Grafana. Tests use `new TaskRunner(store, Runnable::run,
  new SimpleMeterRegistry(), new SimpleTracer())` (`TaskRunnerTracingUTC`, `micrometer-tracing-test`).

## ExifTool and metadata (core)

- `ExifToolService` is the only place the admin and file backends run ExifTool. It wraps `com.github.mjeanroy:exiftool-lib` with a pool of
  persistent `exiftool -stay_open True -@ -` processes (`StayOpenExifToolStrategy`, one per `vempain.exiftool.pool-size`, closed after
  `vempain.exiftool.idle-timeout-ms` and restarted by the next command; `vempain.exiftool.stay-open=false` falls back to one process per
  command). Two deliberate deviations from the library: the library's own stay-open strategy starts the process with `-sep`, which turns the
  list tags of the first command into joined strings instead of JSON arrays, and it never reads stderr, so warnings would be invisible and
  could block the pipe; `MergedOutputCommandExecutor` merges stderr into stdout instead. The exiftool path is `vempain.exiftool.path`
  (`exiftool` on the PATH by default; the admin backend requires an absolute path and checks it in `SetupVerification`).
- Reads: `readMetadataJson`/`readMetadata` (the full `-a -u -ee -api RequestAll=3 -g1 -J` output, a JSON array with one grouped object) and
  `readTagsJson`/`readTag` (`-j -TAG`). Writes change the file in place: `copyAllTags` (`-TagsFromFile src -all:all`), `writeValue`
  (`-GROUP:KEY=value`, names validated with `isValidTagName`, groups `ExifTool`/`File`/`System`/`Composite` refused by `isWritableGroup`),
  `writeSubjects` (XMP-dc, XMP-lr hierarchical and weighted flat, IPTC keywords), `wipeAll` (`-all=`), `importJson` (`-json=`) and
  `replaceMetadataFromJson` (wipe then import, failures only logged). A write succeeds only when ExifTool reports `N image files updated`;
  otherwise an `IOException` carries ExifTool's output, and unreadable maker notes are retried once with `-m`. Every command is timed as
  `vempain.tool.duration{tool=exiftool,result}` on the host's `MeterRegistry`. Nothing is cached between commands: a file modified on disk is
  read as it is, which the file backend's refresh of changed originals relies on.
- `MetadataJson` holds every parser of the grouped JSON (descriptions, dates, rights, creator, GPS as `GpsData`, music tags, image size and
  depth) and the per-type fields the file backend scans (`extractFrameRate`, `extractAudioVideoDuration`, `extractAudioBitRate`,
  `extractDocumentPageCount`, `extractFontFamily`, `extractArchiveCompressionMethod`, ... through `firstValue`, a case-insensitive search of
  every group), so one full read serves every field of a file; nothing runs ExifTool again per tag. `collectStandardMetadataAsJson` builds the
  export derivative's metadata from `MetadataEntry` rows and a `RightsAndCreator` fallback. The file backend keeps its FFmpeg codec probe and
  the entity mappings in its own `FileMetadataTool`.
- Tests: `MetadataJsonUTC`, `MetadataJsonParsingUTC`, `MetadataJsonExtendedUTC` (JSON fixtures), `ExifToolServiceUTC` (mocked library
  facade: output interpretation, retry, metrics) and `ExifToolServiceITC` (the real exiftool on ImageIO images, skipped when it is not
  installed: arrays from the first command, concurrency, restart after an idle close).

## Metrics export (core)

- Every backend pushes its Micrometer meters over OTLP/HTTP to the Prometheus receiver of the monitoring environment (`vempain-cluster/monitoring`); nothing is
  scraped, because the backends' management port is routed publicly through Traefik.
- `fi.poltsi.vempain.common.metrics.MetricsExportEnvironmentPostProcessor` (registered in `META-INF/spring.factories`) maps the host's
  `vempain.metrics.*` properties (`enabled`, `url`, `username`, `password`, `certificate`, `step`, filled from the `VEMPAIN_METRICS_*`
  environment variables) to `management.otlp.metrics.export.url|step|headers.Authorization|ssl.bundle` and a PEM trust store bundle
  `spring.ssl.bundle.pem.vempain-metrics` for the self-signed certificate. With `vempain.metrics.enabled=false` (the default) it adds
  nothing, so local runs and tests never export. The hosts add `io.micrometer:micrometer-registry-otlp` and declare the properties; the
  website backend keeps its own copy of the class (it does not depend on this library). Changing the property names is a contract change
  for three backends and the cluster compose files.
- The same class maps `vempain.tracing.*` (`enabled`, `url`, `sampling`, from `VEMPAIN_TRACING_*`) to `management.tracing.enabled`,
  `management.tracing.sampling.probability` and `management.opentelemetry.tracing.export.otlp.endpoint|headers.Authorization|ssl.bundle`,
  reusing the metrics credentials and certificate: the Tempo receiver of the monitoring environment sits behind the same basic auth and
  self-signed certificate. The hosts add `spring-boot-starter-opentelemetry`; traces propagate as W3C `traceparent`.

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
- Java is indented with 4 spaces and has a 160-character line limit (`.editorconfig`, `.config/eclipse-formatter.xml`); do not mass-reformat.
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
