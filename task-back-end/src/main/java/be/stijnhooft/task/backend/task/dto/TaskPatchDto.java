package be.stijnhooft.task.backend.task.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/// The wire shape of a patch.
///
/// `id` is on it in both directions (**D3**): it is client-minted, so the server has nothing to
/// hand back, and without it the client cannot name the patch it wants to undo. `sequence` joins
/// it on the read side in #46.
public record TaskPatchDto(
        UUID id,
        UUID taskId,
        Instant dateTime,
        @Nullable UUID voids,
        Map<String, String> changes) {
}
