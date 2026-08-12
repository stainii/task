/// The mapping rules: how a portal value becomes a `task` value.
///
/// Everything here is a pure function of the archive, deliberately: no Spring context, no database,
/// nothing to stand up. That is what lets the rules with a date boundary in them — [PortalDates]
/// above all — be tested at their boundaries the way `docs/quality-bar.md` requires.
@NullMarked
package be.stijnhooft.task.backend.migration.map;

import org.jspecify.annotations.NullMarked;
