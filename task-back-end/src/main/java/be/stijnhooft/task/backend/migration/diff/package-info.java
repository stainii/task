/// Stored versus folded: what portal's document said a task was, against what its own history says
/// it is.
///
/// The comparison is a pure function of two values and a patch list — no Spring context, no
/// database — so [Cause]'s attribution rules can be tested at their boundaries the way
/// `docs/quality-bar.md` requires, which matters here because the rules decide what a person is
/// asked to look at before their data becomes irreplaceable.
@NullMarked
package be.stijnhooft.task.backend.migration.diff;

import org.jspecify.annotations.NullMarked;
