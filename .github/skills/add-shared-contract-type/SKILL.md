---
name: add-shared-contract-type
description: Add a DTO, enum or REST contract that both the admin and file backends need to vempain-common api/ and roll it out in the mandated release order. Use when a type would otherwise be duplicated or create an API dependency cycle.
---

# Shared contract types in vempain-common

- `api/` (`fi.poltsi.vempain.common.api.*`) holds what both backends need: `FileTypeEnum`, `TagRequest`, `CopyrightRequest`,
  `LocationRequest/Response`, the task DTOs (`TaskAcceptedResponse`, `TaskProgressResponse`, `TaskStatusEnum`) and `TaskAPI`. The
  backend API chain is strictly one way: `vempain-common-api` <- `vempain-admin-backend-api` <- `vempain-file-backend-api`; a type the
  admin API would need from the file API goes here instead.
- DTO style: Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, `@JsonNaming(SnakeCaseStrategy)` from
  `tools.jackson.databind`, `@Schema` on class and fields; JSON snake_case only. Pin the serialised keys in `RequestContractJTC`/
  `ResponseContractJTC` (`TaskResponseContractJTC` for task DTOs).
- Frontend mirror: the task models are mirrored in `vempain-common-frontend` (`src/models`), other shared types in the admin and file
  frontend models; keep field names identical.
- Release order after a contract change: `vempain-common`, then `vempain-admin-backend`, then `vempain-file-backend`; on the frontend side
  `vempain-common-frontend` before the admin and file frontends. Bump `vempain-common` in each consumer's `gradle/libs.versions.toml`.
- Run `./gradlew clean test` (api and core) and document the type in `AGENTS.md`.
