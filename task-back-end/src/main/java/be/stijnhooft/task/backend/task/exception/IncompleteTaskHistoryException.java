package be.stijnhooft.task.backend.task.exception;

import java.util.UUID;

/// Thrown when a patch history folds to a task missing a field it cannot exist without.
///
/// It means the creation patch did not carry every field (TODO-046), which is the one thing that
/// makes the fold's *blank the task and replay* possible. Not a client error to route: a client
/// sending an incomplete creating patch is a `400` at the door (ADR-0004, #46), so reaching here
/// means the history in the database is not foldable.
public class IncompleteTaskHistoryException extends RuntimeException {

    public IncompleteTaskHistoryException(UUID taskId, String field) {
        super("The patch history of task " + taskId + " folds to a task without a " + field
                + ". The creation patch must carry every field.");
    }
}
