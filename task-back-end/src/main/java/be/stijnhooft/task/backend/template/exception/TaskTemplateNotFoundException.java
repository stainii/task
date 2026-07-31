package be.stijnhooft.task.backend.template.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Task template not found")
public class TaskTemplateNotFoundException extends RuntimeException {
    public TaskTemplateNotFoundException(UUID id) {
        super("Task template with id " + id + " not found.");
    }
}
