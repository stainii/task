package be.stijnhooft.task.backend.task.exception;

/// A stream opened with `?since=` and no `epoch`.
///
/// Answers `400` rather than defaulting the epoch to the server's own. A cursor whose lineage is
/// assumed to be the current one is a cursor that can never be found stale, which turns the epoch
/// off for exactly the client that needed it (ADR-0004). The two halves travel together or not at
/// all.
public class CursorWithoutEpochException extends RuntimeException {

    public CursorWithoutEpochException() {
        super("A 'since' cursor must be presented with the 'epoch' it was recorded in.");
    }
}
