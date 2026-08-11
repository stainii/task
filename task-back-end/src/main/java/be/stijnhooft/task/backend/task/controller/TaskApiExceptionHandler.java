package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.exception.CursorWithoutEpochException;
import be.stijnhooft.task.backend.task.exception.InvalidPatchException;
import be.stijnhooft.task.backend.task.exception.OrphanPatchException;
import be.stijnhooft.task.backend.task.exception.PatchTooLargeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

/// The error contract for the sync API - the `@ControllerAdvice` that had never existed (**D5**).
///
/// **For an offline-first client this is not cosmetic.** The outbox drains strictly in order and
/// decides what to do from the status alone: a `4xx` means the patch is permanently wrong, so drop
/// it and keep going, while a `5xx` means the patch is fine and the world is not, so stall and
/// preserve order (ADR-0004). Everything arriving as `500` therefore reads as *the server is down*,
/// and a patch for a task that no longer exists retries forever, freezing every write behind it on
/// that device while the app still looks fine.
///
/// Scoped to this module's two controllers rather than applied globally: `template` carries its own
/// `@ResponseStatus` mappings, and an advice reaching across modules would be an outbound dependency
/// out of `task`, which ADR-0003 keeps free of them.
@Slf4j
@RestControllerAdvice(assignableTypes = {TaskController.class, TaskPatchController.class})
public class TaskApiExceptionHandler {

    /// `404`: the patch names a task that does not exist and does not create it. Drop and continue.
    @ExceptionHandler(OrphanPatchException.class)
    public ProblemDetail orphan(OrphanPatchException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    /// `400`: the patch is malformed and always will be. Drop, continue, and show it to the human -
    /// this is the one status behind which real work can be lost.
    @ExceptionHandler({InvalidPatchException.class, CursorWithoutEpochException.class})
    public ProblemDetail invalid(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, e);
    }

    /// `400` for a body that never became a patch: a missing `changes` map, an absent id, a date-time
    /// that does not parse. This used to be the `NullPointerException` in D5.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail unreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(PatchTooLargeException.class)
    public ProblemDetail tooLarge(PatchTooLargeException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, e);
    }

    private ProblemDetail problem(HttpStatus status, Exception e) {
        log.info("{} on the sync API: {}", status.value(), e.getMessage());
        return ProblemDetail.forStatusAndDetail(status, String.valueOf(e.getMessage()));
    }
}
