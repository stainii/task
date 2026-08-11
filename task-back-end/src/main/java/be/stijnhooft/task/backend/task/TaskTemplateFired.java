package be.stijnhooft.task.backend.task;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/// The modulith's **one** application event: a template came round and rendered itself
/// ([ADR-0002](../../../../../../../../docs/adr/0002-one-application-event-published-as-a-fact.md)).
///
/// ### Why it lives in `task`
///
/// It is published by `template` and listened to by `task`, and a listener depends on the module
/// owning the event type. `template` must already read `task` — the firing predicate asks it when
/// this template was last done — so declaring the type in `template` would close a cycle and fail
/// `ApplicationModules.verify()`. Here it is a deliberate **inbound port**: every arrow points at
/// `task`, and `task` has no outbound module dependency at all. It is still *named* for the
/// publisher's fact, in the past tense.
///
/// ### Why it carries descriptions and not tasks
///
/// It replaced `TaskCreationRequestedEvent(List<Task>)`, which handed `task` a pre-built `Task`
/// and left the listener nothing to do but save it. That payload is why `Task` could not be made
/// internal: two classes in `template` had to construct one. **A payload is self-contained and is
/// never another module's aggregate**, so what travels is the *rendered* description of each task —
/// `${…}` already substituted, day offsets already resolved to real dates — and building a `Task`
/// from it stays entirely inside `task`.
///
/// The resemblance to `Task`'s fields is intended: this record is the module contract, and it is
/// allowed to drift from `Task`'s internals.
///
/// @param templateId  provenance, written onto every task of the firing
/// @param occurrenceId  the group key naming this one firing. An occurrence is derived and never
///                      stored, so this key is all that remains of it.
/// @param firingDate  **the date the template came round** — today for a template run by hand, and
///                    for a scheduled one the rule's date, which after an outage is in the past.
///                    It becomes the tasks' creation date, because *the firing date is the task's
///                    creation date* is the only record of it there is.
/// @param context  the template's context, rendered once for the whole firing. A context never
///                 varies inside a template.
/// @param definitions  one per task to create, never empty.
public record TaskTemplateFired(
        UUID templateId,
        UUID occurrenceId,
        LocalDate firingDate,
        String context,
        List<RenderedDefinition> definitions) {

    public TaskTemplateFired {
        definitions = List.copyOf(definitions);
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("Template " + templateId + " fired without rendering any task.");
        }
    }

    /// One task of the firing, fully resolved. Nothing here needs a variable map, a definition or a
    /// clock to be understood.
    ///
    /// @param startDate  never null: a task must have one, and a definition with no start offset
    ///                   starts on the firing date.
    /// @param dueDate  null when the definition sets no due offset and the trigger supplies no
    ///                 default — a task simply without a due date.
    public record RenderedDefinition(
            String name,
            @Nullable String description,
            Importance importance,
            LocalDate startDate,
            @Nullable LocalDate dueDate) {
    }
}
