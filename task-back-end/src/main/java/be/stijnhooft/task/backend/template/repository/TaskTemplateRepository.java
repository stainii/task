package be.stijnhooft.task.backend.template.repository;

import be.stijnhooft.task.backend.template.TaskTemplate;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TaskTemplateRepository extends CrudRepository<TaskTemplate, UUID> {
}
