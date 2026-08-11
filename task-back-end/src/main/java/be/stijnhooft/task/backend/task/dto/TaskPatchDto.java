package be.stijnhooft.task.backend.task.dto;

import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/// The wire shape of a patch, in both directions.
///
/// `id` is on it both ways (**D3**): it is client-minted, so the server has nothing to hand back,
/// and without it the client cannot name the patch it wants to undo. `sequence` is the read half -
/// server-assigned, ignored on the way in (see `TaskPatchMapper`), and the number a client's cursor
/// is made of.
///
/// The `@NotNull`s are the door. `changes` missing from the body used to reach the mapper and blow
/// up as a `500` (**D5**); it is a `400`, because a patch that cannot be read is a patch that will
/// never be accepted, and the difference decides whether the client's outbox drops it or stalls on
/// it forever.
public record TaskPatchDto(
        @NotNull UUID id,
        @NotNull UUID taskId,
        @NotNull Instant dateTime,
        @Nullable Long sequence,
        @Nullable UUID voids,
        @NotNull Map<String, String> changes) {
}
