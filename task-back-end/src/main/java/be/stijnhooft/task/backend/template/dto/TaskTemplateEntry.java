package be.stijnhooft.task.backend.template.dto;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.Map;

/// What running a manual template by hand takes: the variables, and **one** anchor date.
///
/// It used to take two — a start date and a due date for "the main task" — and the gap between them
/// set every definition's duration at run time. Under one anchor each definition owns its own
/// duration, which is where it belongs: how long *"send the preparation mail"* takes is a property
/// of the template, not something to re-decide at each workshop
/// ([ADR-0013 §104](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)).
///
/// The anchor's *wording* is the template's, on its [be.stijnhooft.task.backend.template.domain.Trigger.Manual].
public record TaskTemplateEntry(
        Map<String, String> variables,
        @Nullable LocalDate anchorDate) {

    public TaskTemplateEntry {
        variables = Map.copyOf(variables);
    }
}
