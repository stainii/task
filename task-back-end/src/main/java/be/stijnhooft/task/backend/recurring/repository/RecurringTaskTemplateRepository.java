package be.stijnhooft.task.backend.recurring.repository;

import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RecurringTaskTemplateRepository extends CrudRepository<RecurringTaskTemplate, UUID> {
}

