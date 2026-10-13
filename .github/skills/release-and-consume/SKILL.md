---
name: release-and-consume
description: Release vempain-common (api and core) to GitHub Packages or verify an unreleased build in the admin and file backends through the local Maven repository. Use whenever a change here must reach a backend.
---

# Release vempain-common and consume it

- Artifacts `vempain-common-api` and `vempain-common-core`; CI (`spring-boot-library.yaml` of `vempain-workflows`) publishes on `main` with the
  version from `VERSION` and tags. Locally the version is `-PreleaseVersion=<version>` (the catalog placeholder is `0.0.1-SNAPSHOT`).
- Consumers pin `vempain-common` in `gradle/libs.versions.toml`: `vempain-admin-backend`, `vempain-file-backend`. Release order:
  common, admin backend, file backend (the admin API is a dependency of the file backend).
- Verify before publishing: `./gradlew -PreleaseVersion=<next> publishToMavenLocal -x test` here, bump the consumer catalog, and build the
  consumer with an init script that adds `mavenLocal()` first to `allprojects.repositories` (`./gradlew -I local-maven.init.gradle
  :service:test`); the consumers' build files list only GitHub Packages, and GitHub Packages credentials (`gpr.user`/`gpr.token`) are still
  needed for the other artifacts.
- Keep Java, Spring Boot and Gradle wrapper versions identical to the other Java repositories (`gradle/libs.versions.toml`).
- Describe behavioural changes of the task facility in the consumers' `AGENTS.md` when they rely on them (for example the transaction
  behaviour of progress writes).
