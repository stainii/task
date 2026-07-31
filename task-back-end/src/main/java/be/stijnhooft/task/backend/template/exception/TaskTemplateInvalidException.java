package be.stijnhooft.task.backend.template.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "Task template is invalid")
public class TaskTemplateInvalidException extends RuntimeException {
    public TaskTemplateInvalidException(String message) {
        super(message);
    }
}
