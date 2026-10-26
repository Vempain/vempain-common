-- Incremental change of the shared task facility (fi.poltsi.vempain.common.task): task lanes (TaskLane). Hosts that already carry
-- task_tables.sql copy this file into their Flyway tree with their next free version (the file backend as V1013__task_lanes.sql, the
-- admin backend as db/migration/admin/V1010__task_lanes.sql); task_tables.sql already contains the columns for new hosts.
ALTER TABLE task_record
    ADD COLUMN lane       VARCHAR(80),
    ADD COLUMN lane_limit INTEGER;

CREATE INDEX task_record_lane_idx ON task_record (lane, status);
