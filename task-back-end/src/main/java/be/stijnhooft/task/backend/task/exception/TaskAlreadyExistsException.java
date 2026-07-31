package be.stijnhooft.task.backend.task.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(value = HttpStatus.CONFLICT, reason = "Task already exists")
public class TaskAlreadyExistsException extends RuntimeException {
    public TaskAlreadyExistsException(UUID id) {
        super("Task with id " + id + " already exists.");
    }
}
