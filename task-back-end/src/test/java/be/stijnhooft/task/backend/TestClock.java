package be.stijnhooft.task.backend;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/// A clock a test can move, in the same zone the application pins in `application.yml`.
///
/// It exists so date-boundary behaviour is asserted by moving time rather than by waiting for
/// it, which is the whole reason `Clock` is a bean (#44). Prefer this over a Mockito mock: a
/// mocked `Clock` returns `null` for whichever of `instant()` / `getZone()` a test forgets to
/// stub, and the failure surfaces as an NPE somewhere else entirely.
public class TestClock extends Clock {

    /// Kept in step with `task.time-zone`. A test asserting on a date boundary in another zone
    /// would be asserting on something the application never does.
    public static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

    private Instant instant;
    private final ZoneId zone;

    private TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /// Fixed at noon on the given date - noon, so that a test moving whole days cannot trip
    /// over a daylight-saving hour.
    public static TestClock atNoonOn(LocalDate date) {
        return new TestClock(date.atTime(LocalTime.NOON).atZone(ZONE).toInstant(), ZONE);
    }

    public void moveTo(LocalDate date) {
        this.instant = date.atTime(LocalTime.NOON).atZone(zone).toInstant();
    }

    public LocalDate today() {
        return LocalDate.now(this);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

}
