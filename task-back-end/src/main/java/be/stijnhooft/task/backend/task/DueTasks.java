package be.stijnhooft.task.backend.task;

import java.time.LocalDate;
import java.util.List;

/// What `task` will answer about a day's work, and nothing else — the second purpose-built port on
/// this module, after [TaskOccurrences]
/// ([ADR-0003](../../../../../../../../docs/adr/0003-two-modules-with-package-visibility-as-the-boundary.md),
/// [ADR-0012](../../../../../../../../docs/adr/0012-one-push-at-0730-derived-not-stored.md)).
///
/// `notification` asks it at 07:30 to write one push; `task` never calls out. The dependency runs
/// `notification → task` and no event is minted, because ADR-0003's rule is mechanical: **an event
/// cannot return a value**, and this is a question with an answer.
public interface DueTasks {

    /// ***What is due that day?*** — names, in the order they should be read out, and deliberately
    /// nothing else.
    ///
    /// ADR-0012 sketched this as `tasksDueOn(LocalDate)`. It returns names rather than tasks because
    /// a notification names what fits and a bare count is a nag: the id, the description and the
    /// patch history have no reader here, and handing them over would make this a window onto the
    /// repository rather than a port. A deep link would need the id — ADR-0012 ruled that tapping
    /// the notification opens the overview, so there is none.
    ///
    /// **Open tasks only.** Something completed at 07:00 is not announced at 07:30.
    ///
    /// Due, never overdue: a task announces itself on its due day and never again. ADR-0006's
    /// overview is what carries overdue work, always and however much of it, which is the whole
    /// difference from the mail this feature replaced — 8,201 of them, 4% ever read.
    List<String> namesOfTasksDueOn(LocalDate date);
}
