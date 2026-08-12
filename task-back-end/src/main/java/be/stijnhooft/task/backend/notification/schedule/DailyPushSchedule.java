package be.stijnhooft.task.backend.notification.schedule;

import be.stijnhooft.task.backend.notification.service.DailyPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/// **07:30, in Brussels** — all of the *when*, none of the *what*, on ADR-0016's split. The push
/// itself is `DailyPushService`, a plain service a test drives on a fixed date.
///
/// ### The zone is the application's, not the container's
///
/// `zone` reads `task.time-zone`, the same property `config/TimeConfig` feeds the `Clock` bean.
/// Without it this cron runs in the container's default, which is UTC, so *07:30* would land at
/// **09:30 local in summer** and the day would roll over at 02:00
/// ([ADR-0012](../../../../../../../../docs/adr/0012-one-push-at-0730-derived-not-stored.md)).
/// Tolerable for a batch job nobody watches; not tolerable for something that buzzes a phone.
///
/// A cron and not a fixed delay, precisely because this one *is* a calendar event: the due check is
/// a state comparison and does not care when it runs, while a notification at 07:30 is the whole
/// feature. And it lands after the hourly tick has created the day's tasks, which ADR-0016 made
/// true by construction — anything becoming due at the date boundary exists by 01:00.
///
/// ### The knob exists for the suite
///
/// The suite would otherwise carry a schedule that fires against the shared Postgres and posts to
/// the internet if a run happened to straddle 07:30. `task.daily-push.enabled=false` in
/// `src/test/resources/application.properties`; production never sets it.
@Configuration
@ConditionalOnProperty(name = "task.daily-push.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class DailyPushSchedule {

    /// A constant, and referenced by `DailyPushScheduleTest`, so what is asserted is the expression
    /// this annotation actually uses rather than a copy of it that can drift.
    public static final String AT_HALF_PAST_SEVEN = "0 30 7 * * *";

    private final DailyPushService dailyPushService;

    @Scheduled(cron = AT_HALF_PAST_SEVEN, zone = "${task.time-zone}")
    public void tick() {
        dailyPushService.pushWhatIsDueToday();
    }
}
