-- Reference schema of the shared task facility (fi.poltsi.vempain.common.task). Copy this file into the Flyway migration tree
-- of the consuming service with the next free version of that service (the file backend has it as V1009__durable_tasks.sql,
-- the admin backend as db/migration/admin/V1004__task_tables.sql). The JPA entities validate the schema at startup.
CREATE TABLE task_record
(
	task_id          VARCHAR(36) PRIMARY KEY,
	owner_id         BIGINT,
	task_type        VARCHAR(80)              NOT NULL,
	title            VARCHAR(500)             NOT NULL,
	payload          TEXT                     NOT NULL,
	status           VARCHAR(20)              NOT NULL,
	total_steps      BIGINT                   NOT NULL DEFAULT 0,
	completed_steps  BIGINT                   NOT NULL DEFAULT 0,
	failed_steps     BIGINT                   NOT NULL DEFAULT 0,
	reverted_steps   BIGINT                   NOT NULL DEFAULT 0,
	message          TEXT,
	error_message    TEXT,
	result           TEXT,
	created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
	started_at       TIMESTAMP WITH TIME ZONE,
	finished_at      TIMESTAMP WITH TIME ZONE,
	cancel_requested BOOLEAN                  NOT NULL DEFAULT FALSE,
	worker_id        VARCHAR(100),
	lease_until      TIMESTAMP WITH TIME ZONE,
	heartbeat_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX task_record_owner_created_idx ON task_record (owner_id, created_at DESC);
CREATE INDEX task_record_claim_idx ON task_record (status, lease_until, created_at);

CREATE TABLE task_compensation
(
	compensation_id BIGSERIAL PRIMARY KEY,
	task_id         VARCHAR(36)              NOT NULL REFERENCES task_record (task_id) ON DELETE CASCADE,
	description     TEXT                     NOT NULL,
	command_type    VARCHAR(100)             NOT NULL,
	payload         TEXT                     NOT NULL,
	completed       BOOLEAN                  NOT NULL DEFAULT FALSE,
	created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX task_compensation_task_idx ON task_compensation (task_id, compensation_id DESC);
