package be.stijnhooft.task.backend.template.dto;

import be.stijnhooft.task.backend.template.domain.StoredTrigger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/// The wire shape of a task template.
///
/// The trigger crosses the wire in its **flat** form ([StoredTrigger]) rather than as a polymorphic
/// JSON object: one shape for the table and the API means one conversion to keep honest instead of
/// two.
///
/// ### Three fields the client sends and the server ignores
///
/// `id` is the client's on creation and the URL's on update. **`active` and `activeSince` are read
/// only** — they are echoed back so the authoring screen can show them, and discarded on the way in.
/// Neither is a value a client can know: `activeSince` is *the date this template began firing under
/// its current rule*, and `active` is changed through `POST /{id}/deactivation` and
/// `/{id}/reactivation` precisely so that changing it always writes `activeSince`. Accepting it here
/// would have made a `PUT` a second, silent path to reactivation — one that leaves a calendar
/// template free to catch up on the months it spent switched off.
///
/// A read-only field is kept in the record rather than split into a request and a response shape:
/// one wire type for a resource is what the front-end's store expects, and the rule is stated here
/// and enforced in one place (`TaskTemplateMapper`).
public record TaskTemplateDto(
        @Nullable UUID id,

        @NotBlank String name,

        @NotBlank String context,

        /// Read-only. See above.
        boolean active,

        /// Read-only. See above.
        @Nullable LocalDate activeSince,

        @NotNull StoredTrigger trigger,

        /// **At least one.** A template with no definitions renders no tasks, so it would throw once
        /// an hour for ever — [#49](https://github.com/stainii/task/issues/49) left five such rows
        /// behind by `PUT`ting exactly this. The domain refuses it as well
        /// (`TaskTemplate#validateForSaving`); this is the same rule stated where a client can be
        /// told about it in one round trip.
        @NotEmpty @Valid List<TaskDefinitionDto> taskDefinitions) {
}
