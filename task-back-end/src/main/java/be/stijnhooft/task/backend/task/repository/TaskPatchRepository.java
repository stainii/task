package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.task.TaskPatch;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskPatchRepository extends CrudRepository<TaskPatch, UUID> {

    /// Catch-up, read on the **server's** clock.
    ///
    /// This replaced `findByDateTimeAfter`, which queried the client-minted `dateTime`: a patch
    /// written offline on Monday and uploaded on Wednesday is never delivered to a client whose
    /// cursor has already passed Tuesday - permanently, and with nothing to see (ADR-0004).
    List<TaskPatch> findBySequenceGreaterThanOrderBySequenceAsc(long sequence);
}
