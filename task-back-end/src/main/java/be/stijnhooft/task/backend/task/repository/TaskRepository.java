package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.domain.TaskStatus;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends CrudRepository<Task, UUID> {
    List<Task> findByStatus(TaskStatus taskStatus);
}
