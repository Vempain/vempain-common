package fi.poltsi.vempain.common.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "task_compensation")
@Getter
@Setter
@NoArgsConstructor
public class TaskCompensationEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "compensation_id")
	private Long    id;
	@Column(name = "task_id", nullable = false, length = 36)
	private String  taskId;
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(nullable = false)
	private String  description;
	@Column(name = "command_type", nullable = false, length = 100)
	private String  commandType;
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(nullable = false)
	private String  payload;
	@Column(nullable = false)
	private boolean completed;
	@Column(nullable = false)
	private Instant createdAt;
}
