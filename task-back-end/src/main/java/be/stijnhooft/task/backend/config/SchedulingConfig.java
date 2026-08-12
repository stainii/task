package be.stijnhooft.task.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/// **Where scheduling itself is switched on**, and the pool everything scheduled runs on.
///
/// [#49](https://github.com/stainii/task/issues/49) put `@EnableScheduling` and a named pool on
/// `template`'s `DueCheckSchedule`, and said in as many words that resolving the unique
/// `TaskScheduler` was safe *"only until a second such bean"*. ADR-0012's 07:30 push is that second
/// bean, so the two things that were never `template`'s are moved here:
///
/// - **`@EnableScheduling`**, because it was reachable through `@ConditionalOnProperty` on the due
///   check. Setting `task.due-check.enabled=false` — which the whole test suite does — did not
///   disable one schedule, it disabled **scheduling**. A second module's job would have silently
///   never run, in a context that starts cleanly and logs nothing. That is this map's *guarantee
///   broken by something outside the code* shape, pre-installed, and it is deleted rather than
///   documented.
/// - **the pool**, because with two schedulers on the classpath (this one and `sse-`) neither
///   `@Scheduled` annotation could resolve one by type, and the SSE pool is not somewhere an hourly
///   sweep belongs.
///
/// Each module still owns its own *when*: `DueCheckSchedule` and `DailyPushSchedule` are unchanged
/// in every other respect, and neither knows this class exists.
///
/// Two threads, not one: the hourly due check and the 07:30 push can coincide — 07:30 is inside the
/// hour the tick may run in — and a push waiting on a sweep would arrive at whatever time the sweep
/// finished.
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    @Bean
    public TaskScheduler applicationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("scheduled-");
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(applicationScheduler());
    }
}
