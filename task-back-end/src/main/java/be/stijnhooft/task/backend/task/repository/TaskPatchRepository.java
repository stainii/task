package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.task.TaskPatch;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskPatchRepository extends CrudRepository<TaskPatch, UUID> {
    List<TaskPatch> findByDateTimeAfter(LocalDateTime since);
}
