package be.stijnhooft.task.backend.recurring.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "Updating the id is not allowed")
public class UpdatingIdIsNotAllowedException extends RuntimeException {
}
