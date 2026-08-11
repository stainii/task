package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.task.TaskPatch;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskPatchRepository extends CrudRepository<TaskPatch, UUID> {

    /// Replaced by a sequence-based query in #46: querying the *client's* clock never delivers a
    /// patch written offline before the reader's cursor. Kept only until that lands.
    List<TaskPatch> findByDateTimeAfter(Instant since);
}
