package fi.poltsi.vempain.common.task.repository;

import java.time.Instant;

import jakarta.transaction.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import fi.poltsi.vempain.common.task.entity.TaskRecordEntity;

@Repository
public interface TaskRecordRepository extends JpaRepository<TaskRecordEntity, String> {

    /** Advisory lock key of {@link #lockClaims(long)}; one value for every host, the lock is per database. */
    long CLAIM_LOCK_KEY = 0x5645_4D50_4149_4E01L;

    /**
     * Takes a transaction level advisory lock so that only one claim runs at a time in the database: {@link #claimNext} counts the running tasks of a lane, and
     * two claims counting at the same time could both admit a task into a full lane. Call it in the transaction of the claim.
     */
    @Transactional
    @Query(value = "SELECT pg_advisory_xact_lock(:key) IS NULL", nativeQuery = true)
    boolean lockClaims(@Param("key") long key);

    /**
     * Selects and claims one task while holding the row lock. Expired leases are eligible again; the worker id is a fencing token. A task in a lane is only
     * claimed while fewer tasks of that lane than its limit are running (held by a live lease); older queued tasks of a full lane do not block the tasks behind
     * them that are in no lane or in another lane.
     */
    @Transactional
    @Query(value = """
            WITH candidate AS (
              SELECT t.task_id FROM task_record t
              WHERE (t.status = 'QUEUED'
                     OR (t.status IN ('RUNNING','CANCELLING') AND t.lease_until < CURRENT_TIMESTAMP))
                AND (t.lane IS NULL
                     OR (SELECT COUNT(*) FROM task_record r
                          WHERE r.lane = t.lane
                            AND r.task_id <> t.task_id
                            AND r.status IN ('RUNNING','CANCELLING')
                            AND r.lease_until >= CURRENT_TIMESTAMP) < COALESCE(t.lane_limit, 1))
              ORDER BY t.created_at
              FOR UPDATE OF t SKIP LOCKED
              LIMIT 1
            )
            UPDATE task_record t
               SET worker_id = :workerId,
                   lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
                   heartbeat_at = CURRENT_TIMESTAMP,
                   status = CASE WHEN t.status = 'CANCELLING' THEN 'CANCELLING' ELSE 'RUNNING' END
             WHERE t.task_id IN (SELECT task_id FROM candidate)
            RETURNING *""", nativeQuery = true)
    TaskRecordEntity claimNext(@Param("workerId") String workerId, @Param("leaseSeconds") long leaseSeconds);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE task_record SET cancel_requested = TRUE,
                   status = CASE WHEN status = 'QUEUED' THEN 'QUEUED' ELSE 'CANCELLING' END
             WHERE task_id = :id AND status IN ('QUEUED','RUNNING','CANCELLING')""", nativeQuery = true)
    int requestCancel(@Param("id") String id);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE task_record SET heartbeat_at = CURRENT_TIMESTAMP,
                   lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second')
             WHERE task_id = :id AND worker_id = :workerId
                   AND status IN ('RUNNING','CANCELLING')""", nativeQuery = true)
    int heartbeat(@Param("id") String id, @Param("workerId") String workerId, @Param("leaseSeconds") long leaseSeconds);

    @Modifying
    @Transactional
    @Query("""
            delete from TaskRecordEntity t
            where t.status in (
                fi.poltsi.vempain.common.api.TaskStatusEnum.COMPLETED,
                fi.poltsi.vempain.common.api.TaskStatusEnum.FAILED,
                fi.poltsi.vempain.common.api.TaskStatusEnum.CANCELLED
            )
            and t.finishedAt < :cutoff""")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);

    /**
     * Tasks waiting for a worker; read by the {@code vempain.tasks.queued} gauge.
     */
    long countByStatus(fi.poltsi.vempain.common.api.TaskStatusEnum status);
}
