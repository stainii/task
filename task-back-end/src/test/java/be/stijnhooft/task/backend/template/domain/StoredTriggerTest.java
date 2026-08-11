package be.stijnhooft.task.backend.template.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The sealed [Trigger] and the columns of `task_template` are two representations of one thing,
/// and this is the only place they can drift apart. Every shape round-trips, and the enumeration is
/// exhaustive by construction: a fifth `Trigger` or a fifth [CalendarRule] makes
/// [StoredTrigger#of] fail to compile long before it reaches here.
class StoredTriggerTest {

    static List<Trigger> everyShape() {
        return List.of(
                new Trigger.Manual("When is the workshop?"),
                new Trigger.Manual(null),
                new Trigger.MinMax(10, 21),
                // min == max: due immediately, which ten of the 44 real templates say.
                new Trigger.MinMax(720, 720),
                new Trigger.Calendar(new CalendarRule.Days(3)),
                new Trigger.Calendar(new CalendarRule.Weeks(2, Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY))),
                new Trigger.Calendar(new CalendarRule.Months(12, 31)),
                new Trigger.Calendar(new CalendarRule.NthWeekday(1, CalendarRule.Ordinal.LAST, DayOfWeek.FRIDAY)));
    }

    @ParameterizedTest
    @MethodSource("everyShape")
    void roundTripsThroughItsColumns(Trigger trigger) {
        assertThat(StoredTrigger.of(trigger).toTrigger()).isEqualTo(trigger);
    }

    /// Min and max stay readable in SQL, which is the whole reason this is columns rather than one
    /// JSON blob: ADR-0005's importer and whoever checks its work both `SELECT` on them.
    @Test
    void keepsMinAndMaxInTheirOwnColumns() {
        var stored = StoredTrigger.of(Trigger.MinMax.ofIntervalAndWindow(45, 3));

        assertThat(stored.type()).isEqualTo(StoredTrigger.Type.MIN_MAX);
        assertThat(stored.minDays()).isEqualTo(45);
        assertThat(stored.maxDays()).isEqualTo(48);
    }

    /// A row whose discriminator promises a column it does not carry is corrupt, and says so —
    /// rather than folding into a template that quietly never fires.
    @Test
    void refusesARowThatContradictsItsOwnDiscriminator() {
        var missingInterval = new StoredTrigger(StoredTrigger.Type.CALENDAR, null, null, null,
                StoredTrigger.Rule.DAYS, null, null, null, null);

        assertThatThrownBy(missingInterval::toTrigger)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trigger_calendar_interval");
    }
}
