package be.stijnhooft.task.backend.task;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/// What `task` will answer about a template's tasks, and nothing else
/// ([ADR-0003](../../../../../../../../docs/adr/0003-two-modules-with-package-visibility-as-the-boundary.md)).
///
/// **Queries go direct, facts go by event**, and the line is mechanical rather than stylistic: an
/// event cannot return a value. Everything the firing side needs to know is a question with an
/// answer, so it is asked here rather than pushed as a `TaskClosed` event nobody would be waiting
/// for.
///
/// It is a purpose-built port, not a window onto the repository: three questions, all of them
/// derived from tasks and their patch history, because [ADR-0001](../../../../../../../../docs/adr/0001-one-task-aggregate-with-triggered-templates.md)
/// deleted `Execution` and `activeTask` and made an occurrence something you compute rather than
/// something you store.
///
/// `taskTemplateId` and `occurrenceId` are therefore part of `task`'s exposed vocabulary. They are
/// UUID columns on a task, not a code dependency on `template`, so no arrow points outward.
public interface TaskOccurrences {

    /// *Does this template have a task still open?* — the suppression rule, unchanged since
    /// ADR-0001 and load-bearing for both scheduled triggers: a template does not come round again
    /// while the last time it did is still sitting there undone.
    ///
    /// Named for the occurrence in ADR-0003 and read at task level ever since
    /// ([ADR-0011](../../../../../../../../docs/adr/0011-completion-is-a-task-fact-the-template-reads.md)):
    /// *any* open task of the template suppresses it, and a firing whose tasks ended differently
    /// needs no rule to adjudicate.
    boolean hasOpenOccurrence(UUID templateId);

    /// ***When did I last actually do this?*** — history, and the only honest answer to it.
    ///
    /// The latest `completedOn` among the template's **completed** tasks: the day the work happened,
    /// which is a domain value the person completing the task sets and which defaults to today. It
    /// is deliberately not the completing patch's timestamp, and deliberately not a closure —
    /// cancelling is not doing.
    ///
    /// Empty for a template whose tasks have all been cancelled, and for one that has never fired.
    Optional<LocalDate> lastCompletionOf(UUID templateId);

    /// ***When should I next be asked?*** — scheduling, which is a different question, and reading
    /// one answer for both is a bug with a worked example.
    ///
    /// The firing date of the template's most recently **closed** task, completed or cancelled.
    /// *Any* closure ends the round, so a cancelled task buys the template a full interval of quiet
    /// (ADR-0011). Answer this with [#lastCompletionOf] instead and a cancelled min/max task leaves
    /// nothing open to suppress the template while the last completion stays in the past — so it
    /// fires again the next day, and the next, until something is completed.
    ///
    /// **The firing date is the task's creation date**, which is the whole record of a firing that
    /// exists; `TaskTemplateFired#firingDate` is written there precisely so a firing that catches up
    /// on a date it slept through is dated for the date it was for.
    ///
    /// Read as the **latest** firing date among closed tasks rather than by following the closures
    /// themselves: a round is not a unit that closes, and what the predicate compares against is a
    /// bound — the newest date already answered for.
    ///
    /// Empty for a template with nothing closed, which is what makes `activeSince` the seed of a
    /// brand-new template's first firing ([ADR-0017](../../../../../../../../docs/adr/0017-a-calendar-template-fires-for-its-latest-unclosed-date.md)).
    Optional<LocalDate> lastClosureOf(UUID templateId);
}
