package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.template.CalendarRule.Ordinal;
import org.jspecify.annotations.Nullable;
import org.springframework.data.relational.core.mapping.Column;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/// A [Trigger] flattened into **a discriminator plus typed nullable columns**, embedded in
/// `task_template`.
///
/// A single JSON column would have been less code and is rejected on one requirement:
/// [ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)'s
/// importer and the person checking its work both need `min` and `max` to be **readable in SQL**.
/// A trigger you cannot `SELECT` on is a trigger you cannot verify 44 templates' worth of.
///
/// The conversion is total in both directions and pinned by a round-trip test over every shape,
/// because this is the one place where the sealed type and the table can drift apart.
public record StoredTrigger(

        @Column("trigger_type") Type type,

        /// [Trigger.Manual] only.
        @Column("trigger_anchor_label") @Nullable String anchorLabel,

        /// [Trigger.MinMax] only.
        @Column("trigger_min_days") @Nullable Integer minDays,
        @Column("trigger_max_days") @Nullable Integer maxDays,

        /// [Trigger.Calendar] only. Which of the four rules, and the fields that rule uses.
        @Column("trigger_calendar_rule") @Nullable Rule calendarRule,
        @Column("trigger_calendar_interval") @Nullable Integer calendarInterval,

        /// Comma-separated, because [CalendarRule.Weeks] names several and
        /// [CalendarRule.NthWeekday] names one. Stored as text so it stays greppable.
        @Column("trigger_calendar_weekdays") @Nullable String calendarWeekdays,

        @Column("trigger_calendar_day_of_month") @Nullable Integer calendarDayOfMonth,
        @Column("trigger_calendar_ordinal") @Nullable Ordinal calendarOrdinal) {

    public enum Type {
        MANUAL, MIN_MAX, CALENDAR
    }

    public enum Rule {
        DAYS, WEEKS, MONTHS, NTH_WEEKDAY
    }

    public static StoredTrigger of(Trigger trigger) {
        return switch (trigger) {
            case Trigger.Manual manual ->
                    new StoredTrigger(Type.MANUAL, manual.anchorLabel(), null, null, null, null, null, null, null);
            case Trigger.MinMax minMax ->
                    new StoredTrigger(Type.MIN_MAX, null, minMax.min(), minMax.max(), null, null, null, null, null);
            case Trigger.Calendar calendar -> switch (calendar.rule()) {
                case CalendarRule.Days days ->
                        new StoredTrigger(Type.CALENDAR, null, null, null, Rule.DAYS, days.interval(), null, null, null);
                case CalendarRule.Weeks weeks ->
                        new StoredTrigger(Type.CALENDAR, null, null, null, Rule.WEEKS, weeks.interval(), join(weeks.weekdays()), null, null);
                case CalendarRule.Months months ->
                        new StoredTrigger(Type.CALENDAR, null, null, null, Rule.MONTHS, months.interval(), null, months.dayOfMonth(), null);
                case CalendarRule.NthWeekday nth ->
                        new StoredTrigger(Type.CALENDAR, null, null, null, Rule.NTH_WEEKDAY, nth.interval(), nth.weekday().name(), null, nth.ordinal());
            };
        };
    }

    public Trigger toTrigger() {
        return switch (type) {
            case MANUAL -> new Trigger.Manual(anchorLabel);
            case MIN_MAX -> new Trigger.MinMax(
                    required(minDays, "trigger_min_days"),
                    required(maxDays, "trigger_max_days"));
            case CALENDAR -> new Trigger.Calendar(toRule());
        };
    }

    private CalendarRule toRule() {
        var rule = required(calendarRule, "trigger_calendar_rule");
        var interval = required(calendarInterval, "trigger_calendar_interval");
        return switch (rule) {
            case DAYS -> new CalendarRule.Days(interval);
            case WEEKS -> new CalendarRule.Weeks(interval, weekdays());
            case MONTHS -> new CalendarRule.Months(interval, required(calendarDayOfMonth, "trigger_calendar_day_of_month"));
            case NTH_WEEKDAY -> new CalendarRule.NthWeekday(
                    interval,
                    required(calendarOrdinal, "trigger_calendar_ordinal"),
                    weekdays().iterator().next());
        };
    }

    private Set<DayOfWeek> weekdays() {
        var stored = required(calendarWeekdays, "trigger_calendar_weekdays");
        var weekdays = Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(day -> !day.isEmpty())
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (weekdays.isEmpty()) {
            throw new IllegalStateException("A stored calendar trigger names no weekday: trigger_calendar_weekdays is blank.");
        }
        return weekdays;
    }

    private static String join(Set<DayOfWeek> weekdays) {
        return weekdays.stream()
                .sorted()
                .map(DayOfWeek::name)
                .collect(Collectors.joining(","));
    }

    /// A stored trigger missing a column its own discriminator requires is corrupt, not empty. It
    /// fails loudly here rather than folding into a template that quietly never fires.
    private <T> T required(@Nullable T value, String column) {
        if (value == null) {
            throw new IllegalStateException("A stored " + type + " trigger is missing column " + column + ".");
        }
        return value;
    }
}
