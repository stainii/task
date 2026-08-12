package be.stijnhooft.task.backend.notification;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.notification.service.DailyPushService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/// **The one context in the suite where the 07:30 push is switched on**, and it exists because a
/// schedule nobody proves is a comment — the lesson `#10`'s `-Xplugin` line break and `#23`'s Error
/// Prone canary taught twice, and `DueCheckStartupIntegrationTest` a third time.
///
/// It asserts against the **registered** task rather than against the cron string: what is checked
/// is the trigger Spring actually built from the annotation, zone included, so a `zone` that failed
/// to resolve its property cannot pass here.
///
/// And it is asserted by moving a clock, not by waiting for the morning: 07:30 Brussels is **06:30
/// UTC in winter and 05:30 UTC in summer**. Getting that wrong is exactly ADR-0012's reason for
/// pinning the zone — an "07:30" push landing at 09:30 half the year.
@SpringBootTest(properties = "task.daily-push.enabled=true")
class DailyPushScheduleTest extends AbstractIntegrationTestCases {

    private static final LocalDate A_WINTER_DAY = LocalDate.of(2026, 1, 15);
    private static final LocalDate A_SUMMER_DAY = LocalDate.of(2026, 7, 15);

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    /// The push itself is `DailyPushIntegrationTest`'s. This context must not send anything: it
    /// only asks when it would.
    @MockitoBean
    private DailyPushService dailyPushService;

    @Test
    void fires0730LocalTimeInWinter() {
        assertThat(nextFiringAfterNoonOn(A_WINTER_DAY))
                .isEqualTo(Instant.parse("2026-01-16T06:30:00Z"));
    }

    /// The same wall-clock time, an hour earlier in UTC. A cron running in the container's default
    /// zone would fire at the same *UTC* instant all year, which is what makes this the assertion
    /// worth writing.
    @Test
    void fires0730LocalTimeInSummerToo() {
        assertThat(nextFiringAfterNoonOn(A_SUMMER_DAY))
                .isEqualTo(Instant.parse("2026-07-16T05:30:00Z"));
    }

    private Instant nextFiringAfterNoonOn(LocalDate date) {
        return theDailyPushTrigger().nextExecution(new SimpleTriggerContext(TestClock.atNoonOn(date)));
    }

    private Trigger theDailyPushTrigger() {
        return scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .filter(task -> task.toString().contains("DailyPushSchedule"))
                .map(CronTask::getTrigger)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The 07:30 push is not scheduled at all."));
    }
}
