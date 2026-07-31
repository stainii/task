package be.stijnhooft.task.backend.recurring.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Recurring task template not found")
public class RecurringTaskTemplateNotFoundException extends RuntimeException {
    public RecurringTaskTemplateNotFoundException(UUID recurringTaskId) {
        super("Recurring task template with id " + recurringTaskId + " not found.");
    }
}
