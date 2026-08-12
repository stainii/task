package be.stijnhooft.task.backend.template.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/// `409`: this template has tasks, so it is deactivated rather than deleted.
///
/// A conflict rather than a bad request, because the request is well-formed and the refusal is about
/// the state of the world — and because the client's next move is a different call
/// (`POST /{id}/deactivation`), not a corrected payload.
@ResponseStatus(value = HttpStatus.CONFLICT, reason = "Task template has tasks and can only be deactivated")
public class TaskTemplateInUseException extends RuntimeException {
    public TaskTemplateInUseException(UUID id) {
        super("Task template " + id + " has tasks, so it can only be deactivated, never deleted.");
    }
}
