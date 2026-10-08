package fi.poltsi.vempain.common.task.entity;

import fi.poltsi.vempain.common.api.TaskStatusEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "task_record")
@Getter
@Setter
@NoArgsConstructor
public class TaskRecordEntity {
	@Id
	@Column(name = "task_id", nullable = false, length = 36)
	private String         id;
	@Column(name = "owner_id")
	private Long           ownerId;
	@Column(name = "task_type", nullable = false, length = 80)
	private String         type;
	@Column(nullable = false, length = 500)
	private String         title;
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(nullable = false)
	private String         payload;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TaskStatusEnum status;
	@Column(nullable = false)
	private long           totalSteps;
	@Column(nullable = false)
	private long           completedSteps;
	@Column(nullable = false)
	private long           failedSteps;
	@Column(nullable = false)
	private long           revertedSteps;
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	private String         message;
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "error_message")
	private String         errorMessage;
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	private String         result;
	@Column(nullable = false)
	private Instant        createdAt;
	private Instant        startedAt;
	private Instant        finishedAt;
	@Column(nullable = false)
	private boolean        cancelRequested;
	@Column(length = 100)
	private String         workerId;
	private Instant        leaseUntil;
	private Instant        heartbeatAt;
}
