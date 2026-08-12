package be.stijnhooft.task.backend.migration.map;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/// Narrowing portal's date-times to the `LocalDate` the new model holds (TODO-001) — **the single
/// most consequential mapping in the importer**, and the one
/// [ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md) got
/// wrong for half the corpus.
///
/// ### Portal wrote two shapes, not one
///
/// ADR-0005 saw only `LocalDateTime` strings and prescribed truncation. Measured against the frozen
/// archive, `startDateTime` and `dueDateTime` arrive as **two different kinds of value**:
///
/// | shape | count | what it is |
/// |---|---|---|
/// | `2022-09-14T06:00:00.626132` | 11,892 | a local wall-clock time, written by the Java service |
/// | `2024-03-15T23:00:00.000Z` | 9,662 | an **instant in UTC**, written by the browser |
///
/// The second kind is the trap. Of those 9,662 values, **99.7% sit at exactly 22:00 or 23:00 UTC** —
/// 22:00 in summer, 23:00 in winter — which is midnight in `Europe/Brussels` on both sides of the
/// daylight-saving boundary. They are Brussels midnights that `Date.toISOString()` rendered as
/// instants.
///
/// So truncating them *as written* yields **the day before** — for every single one of them, in a
/// corpus where the app's whole job is telling you what is due today. The shift would be silent, it
/// would be permanent after cutover, and no assertion about counts would catch it, because the
/// number of patches is unchanged.
///
/// ### The rule
///
/// **A value that names an instant is converted to `Europe/Brussels` before its date is taken; a
/// value that names a local time already is one.** Both readings agree with what portal *displayed*,
/// which is the only definition of correct available — ADR-0005 §248 notes the discrepancy is not
/// reconstructable afterwards, so this is the last moment it can be got right.
///
/// The zone is a parameter rather than a constant: it comes from the same `task.time-zone` property
/// the `Clock` bean reads (#44), so the importer and the running application can never disagree
/// about what day it is.
public final class PortalDates {

    private PortalDates() {
    }

    /// Narrows one portal date-time string to a date.
    ///
    /// @param value the raw string from a patch's `changes` map
    /// @param zone  the application's zone, from `task.time-zone`
    /// @throws DateTimeParseException when the value is neither an instant nor a local date-time.
    ///                                Deliberately not lenient: ADR-0005 rejected teaching the
    ///                                parser to read dates two ways for data that stops arriving the
    ///                                day after cutover, and a lenient parser eventually swallows a
    ///                                real bug.
    public static LocalDate toLocalDate(String value, ZoneId zone) {
        if (namesAnInstant(value)) {
            return Instant.parse(value).atZone(zone).toLocalDate();
        }
        return LocalDateTime.parse(value).toLocalDate();
    }

    /// The date on which something recorded at this instant happened, in the reader's zone. Used
    /// for `completedOn`, which ADR-0011 defines as *when did I do it* — a domain value, so it is
    /// the day in Brussels, never the day in UTC.
    public static LocalDate toLocalDate(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalDate();
    }

    /// Whether the string carries a zone or offset, making it an instant rather than a wall clock.
    ///
    /// Only `Z` occurs in the archive — all 9,662 of them — but an explicit offset is checked for
    /// too, because the cost of being wrong is a one-day shift that nothing downstream can detect.
    private static boolean namesAnInstant(String value) {
        if (value.endsWith("Z")) {
            return true;
        }
        var time = value.indexOf('T');
        if (time < 0) {
            return false;
        }
        var afterTime = value.substring(time);
        return afterTime.indexOf('+') >= 0 || afterTime.lastIndexOf('-') > 0;
    }
}
