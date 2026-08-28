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
/// It is a purpose-built port, not a window onto the repository: four questions, all of them
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

    /// *Has this template ever produced a task?* — the one question deletion turns on
    /// ([#50](https://github.com/stainii/task/issues/50)).
    ///
    /// A template with tasks is **deactivated, never deleted**: `taskTemplateId` is load-bearing now
    /// that an occurrence is derived, and portal measured what deleting costs — 49% of its recurring
    /// tasks point at a template that no longer exists
    /// ([#35](https://github.com/stainii/task/issues/35)). So deletion survives only for the case
    /// where nothing can be orphaned, and that is **a count, not a judgement** — which is why it is
    /// asked here rather than inferred from the other three. A template whose every task was
    /// cancelled years ago still has history; [#hasOpenOccurrence] would say it is free to delete.
    boolean hasAnyOccurrence(UUID templateId);

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
    /// The template's most recently **closed** task, completed or cancelled, as [LastClosure]'s two
    /// dates: the day it came round and the day you closed it. *Any* closure ends the round, so a
    /// cancelled task buys the template a full interval of quiet (ADR-0011). Answer this with
    /// [#lastCompletionOf] instead and a cancelled min/max task leaves nothing open to suppress the
    /// template while the last completion stays in the past — so it fires again the next day, and
    /// the next, until something is completed.
    ///
    /// **The two dates are not the same date, and #75 is what it cost to conflate them.** This
    /// answered with the firing date alone until
    /// [ADR-0022](../../../../../../../../docs/adr/0022-a-min-max-round-starts-when-you-closed-it.md),
    /// which is the day the task *appeared*: a `MinMax` round starting there is anchored to a grid
    /// inherited from the template's own first firing, which is the calendar it exists in order not
    /// to be. See [LastClosure] for which reader takes which date.
    ///
    /// *Most recently closed* is by the **closure** date — the newest round to have ended. The two
    /// orderings coincide in practice, because suppression serialises a template's firings.
    ///
    /// Empty for a template with nothing closed, which is what makes `activeSince` the seed of a
    /// brand-new template's first firing ([ADR-0017](../../../../../../../../docs/adr/0017-a-calendar-template-fires-for-its-latest-unclosed-date.md)).
    Optional<LastClosure> lastClosureOf(UUID templateId);
}
