package be.stijnhooft.task.backend.task.exception;

/// A patch the server will never be able to accept: an unknown change key, a value that does not
/// parse as its field, a creating patch missing a field the fold cannot do without, or a patch that
/// neither changes nor voids anything.
///
/// Answers `400`, which the client's outbox drops into its visible failed-to-sync list. Permanently
/// wrong, so retrying is the one thing that must not happen (ADR-0004).
public class InvalidPatchException extends RuntimeException {

    public InvalidPatchException(String message) {
        super(message);
    }

    public InvalidPatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
