package be.stijnhooft.task.backend.task.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Task patch not found")
public class TaskPatchNotFoundException extends RuntimeException {
    public TaskPatchNotFoundException(UUID taskPatchId) {
        super("Task patch with id " + taskPatchId + " not found.");
    }
}
