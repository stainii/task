package be.stijnhooft.task.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/// The application's notion of *now*, in one place (TODO-042, TODO-043).
///
/// Portal pinned the zone by mutating the JVM default in `main`: invisible from config and
/// impossible to move in a test. Here the zone is a property and the clock is a bean, so
/// every date-boundary decision in this application is taken in the same zone whatever the
/// host is set to, and a test can move the clock instead of waiting for the calendar.
///
/// `JavaTimeDefaultTimeZone` is promoted to ERROR by `docs/quality-bar.md`, which is what
/// keeps this the only way to ask what day it is: `LocalDate.now()` does not compile.
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${task.time-zone}") ZoneId timeZone) {
        return Clock.system(timeZone);
    }

}
