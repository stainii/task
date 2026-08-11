package be.stijnhooft.task.backend.task.exception;

import java.util.UUID;

/// A patch for a task the server has never heard of, that is not itself a creating patch.
///
/// The first patch for a task id creates it, so an orphan is a client bug rather than a legitimate
/// state: within a device the outbox drains in order, so the create always goes first, and a second
/// device can only learn of a task from the server, which means the create already landed
/// (ADR-0004).
///
/// It answers `404`, which tells the client's outbox to drop it and keep going. That is why it is
/// distinct from an incomplete create, which is a `400`: both drop, but only one of them is worth
/// showing a human.
public class OrphanPatchException extends RuntimeException {

    public OrphanPatchException(UUID taskId) {
        super("Patch names task " + taskId + ", which does not exist, and does not create it.");
    }
}
