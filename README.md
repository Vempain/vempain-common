# Vempain Common component

This component is part of the [Vempain](https://vempain.poltsi.fi/) project. It publishes the API types that more than one Vempain backend needs,
so that the backends do not have to depend on each other's API artifacts.

[AGENTS.md](AGENTS.md) has more detailed orientation and workflow guidance for agents working in this codebase.

## Modules

- `api` publishes `fi.poltsi.vempain:vempain-common-api` to GitHub Packages: plain DTOs and enums plus the REST interface of the task
  facility (`TaskAPI`). No Spring beans, no persistence.
- `core` publishes `fi.poltsi.vempain:vempain-common-core`: the durable background task facility (`fi.poltsi.vempain.common.task`) that the
  admin and file backends host for their long-running actions. Its frontend counterpart is `@vempain/vempain-common-frontend`.

## Contents

| Package                                  | Types                                                     |
|------------------------------------------|-----------------------------------------------------------|
| `fi.poltsi.vempain.common.api`           | `FileTypeEnum` (file classification and mimetype mapping), `TaskStatusEnum` |
| `fi.poltsi.vempain.common.api.request`   | `LocationRequest`, `TagRequest`, `CopyrightRequest`       |
| `fi.poltsi.vempain.common.api.response`  | `LocationResponse`, `TaskAcceptedResponse`, `TaskProgressResponse` |
| `fi.poltsi.vempain.common.rest`          | `TaskAPI`                                                 |
| `fi.poltsi.vempain.common.task` (core)   | `TaskRunner`, `TaskProgressStore`, `TaskProgress`, `TaskController`, `TaskCommandExecutor`, entities and repositories |

Consumers: `vempain-file-backend` (owner of files, tags and locations) and `vempain-admin-backend` (receives ingested files and their metadata).

## Build

```bash
./gradlew clean test
./gradlew :api:publishToMavenLocal :core:publishToMavenLocal -PreleaseVersion=<version>
```

Publishing to GitHub Packages is done by CI from `main`; the version is derived from `VERSION` and the existing Git tags.
