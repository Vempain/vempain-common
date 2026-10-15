package fi.poltsi.vempain.common.task.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import fi.poltsi.vempain.common.task.entity.TaskCompensationEntity;

@Repository
public interface TaskCompensationRepository extends JpaRepository<TaskCompensationEntity, Long> {

    List<TaskCompensationEntity> findByTaskIdOrderByIdDesc(String taskId);

    void deleteByTaskId(String taskId);
}
