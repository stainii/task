package be.stijnhooft.task.backend.template.schedule;

import be.stijnhooft.task.backend.template.service.DueTemplateChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

/// **The tick, and nothing else.** All of the *when*, none of the *what*
/// ([ADR-0016 §137](../../../../../../../../docs/adr/0016-the-due-check-ticks-hourly-and-starts-with-the-app.md)):
/// the check itself is `DueTemplateChecker`, a plain service a test can drive directly.
///
/// `@EnableScheduling` and the pool this runs on used to live here; [#51](https://github.com/stainii/task/issues/51)
/// moved them to `config/SchedulingConfig` when a second module started scheduling — the knob below
/// was switching off *scheduling*, not one schedule.
///
/// ### One annotation does both jobs
///
/// With `fixedDelay`, Spring's first execution lands as soon as the scheduler starts — right after
/// context refresh. So *check on startup* and *check periodically* are the **same annotation**,
/// rather than an `ApplicationReadyEvent` listener beside a cron that has to be kept in step with
/// it. `initialDelay = 0` is technically redundant and is written anyway: it is the line that says
/// the startup check is deliberate.
///
/// `fixedDelay` and not `fixedRate`, because the gap is measured after completion — a slow run can
/// never overlap the next one.
///
/// Startup is **added** to the periodic check and does not replace it: ADR-0007's nightly
/// `compose up -d` recreates a container only when the image or its configuration changed, so on
/// any night with no push to `main` nothing restarts, and a startup-only check would mean a quiet
/// fortnight is a fortnight with nothing firing.
///
/// ### Hourly, and the interval is a constant
///
/// Not a property. A 24-hour delay has no fixed phase — it lands at whatever wall-clock time the
/// container last started — and a tick settling at 08:00 makes ADR-0012's 07:30 push announce what
/// is due today before today's tasks exist. Hourly removes that by construction: anything becoming
/// due at the date boundary exists by 01:00 at the latest. There is no scenario where the box wants
/// a different interval from CI, and portal's configurable `0 0 4 * * *` (REC-018) is deleted
/// rather than carried over.
///
/// ### The one knob, and it exists for the suite
///
/// `initialDelay = 0` otherwise fires in **every** integration test context against the shared
/// Postgres, which is [#10](https://github.com/stainii/task/issues/10)'s isolation problem. So the
/// whole schedule is switched off by
/// `task.due-check.enabled=false` in `src/test/resources/application.properties`, and exactly one
/// test switches it back on to prove the startup fire happens. Production never sets the property.
@Configuration
@ConditionalOnProperty(name = "task.due-check.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class DueCheckSchedule {

    private final DueTemplateChecker dueTemplateChecker;

    @Scheduled(fixedDelay = 1, initialDelay = 0, timeUnit = TimeUnit.HOURS)
    public void tick() {
        dueTemplateChecker.check();
    }

}
