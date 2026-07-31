package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskStatus;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends CrudRepository<Task, UUID> {
    List<Task> findByStatus(TaskStatus taskStatus);
}
