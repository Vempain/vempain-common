package fi.poltsi.vempain.common.task.repository;

import fi.poltsi.vempain.common.task.entity.TaskRecordEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface TaskRecordRepository extends JpaRepository<TaskRecordEntity, String> {
	/**
	 * Selects and claims one task while holding the row lock. Expired leases are eligible again; the worker id is a fencing token.
	 */
	@Transactional
	@Query(value = """
			WITH candidate AS (
			  SELECT task_id FROM task_record
			  WHERE status = 'QUEUED'
			     OR (status IN ('RUNNING','CANCELLING') AND lease_until < CURRENT_TIMESTAMP)
			  ORDER BY created_at
			  FOR UPDATE SKIP LOCKED
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
	@Query("delete from TaskRecordEntity t where t.status in (fi.poltsi.vempain.common.api.TaskStatusEnum.COMPLETED, fi.poltsi.vempain.common.api.TaskStatusEnum.FAILED, fi.poltsi.vempain.common.api.TaskStatusEnum.CANCELLED) and t.finishedAt < :cutoff")
	int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
