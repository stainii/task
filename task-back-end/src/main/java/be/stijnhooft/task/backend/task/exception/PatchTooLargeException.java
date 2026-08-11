package be.stijnhooft.task.backend.task.exception;

/// A patch whose payload is over the cap. Answers `413`, per ADR-0004's write-path contract.
public class PatchTooLargeException extends RuntimeException {

    public PatchTooLargeException(int size, int cap) {
        super("Patch carries " + size + " characters of changes; the cap is " + cap + ".");
    }
}
