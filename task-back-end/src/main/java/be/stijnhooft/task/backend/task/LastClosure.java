package be.stijnhooft.task.backend.task;

import java.time.LocalDate;

/// **The template's most recently closed task, as the two dates that are not the same date**
/// ([ADR-0022](../../../../../../../../docs/adr/0022-a-min-max-round-starts-when-you-closed-it.md)).
///
/// One record rather than two questions on the port, because what it makes visible is the whole
/// point: three lines side by side saying that `TaskTemplate#firingDateOn`'s rule 3 reads
/// [#firedOn], `Trigger.MinMax` reads [#closedOn], and `Trigger.Calendar` reads neither. Until #75
/// one name meant both things, and that is the entire defect — a chore you were later than the
/// interval with came back the hour you ticked it off, already overdue.
///
/// It is also one query per template per tick rather than two, over 43 templates, 24 times a day.
///
/// @param firedOn  **the day the task came round** — its creation date, which is the whole record
///                 of a firing that exists. The bound rule 3 compares a calendar date against: the
///                 newest date already answered for, so a date that passed while a task was open
///                 still comes back exactly once when it is closed (ADR-0017).
/// @param closedOn **the day you closed it** — `completedOn` for a completion, `cancelledOn` for a
///                 cancellation. What `MinMax` counts its interval from, so ticking a chore off
///                 always buys a full `min` days of quiet, including the chore you were three weeks
///                 late with. Backdating `completedOn` moves the schedule with the history, and
///                 that is the point rather than a side effect: someone who corrects the date means
///                 *count from here*.
public record LastClosure(LocalDate firedOn, LocalDate closedOn) {
}
