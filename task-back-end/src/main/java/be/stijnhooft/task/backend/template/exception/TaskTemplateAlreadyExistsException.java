package be.stijnhooft.task.backend.template.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "Task template already exists")
public class TaskTemplateAlreadyExistsException extends RuntimeException {
    public TaskTemplateAlreadyExistsException(UUID id) {
        super("Task template with id " + id + " already exists.");
    }
}
