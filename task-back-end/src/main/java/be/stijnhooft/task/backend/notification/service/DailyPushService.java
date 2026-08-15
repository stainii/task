package be.stijnhooft.task.backend.notification.service;

import be.stijnhooft.task.backend.notification.domain.DailyDigest;
import be.stijnhooft.task.backend.notification.domain.PushSubscription;
import be.stijnhooft.task.backend.notification.webpush.PushOutcome;
import be.stijnhooft.task.backend.notification.webpush.WebPushClient;
import be.stijnhooft.task.backend.task.DueTasks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;

/// **The 07:30 push**: ask `task` what is due today, and if anything is, tell every registered
/// device once ([ADR-0012](../../../../../../../../docs/adr/0012-one-push-at-0730-derived-not-stored.md)).
///
/// A plain service that knows nothing about a schedule — `DailyPushSchedule` is the only thing that
/// does, following ADR-0016's split exactly, so this can be driven from a test on a fixed date
/// instead of at 07:30.
///
/// ### Nothing here remembers anything
///
/// There is no *sent* state, per device or otherwise. A task is due-today on exactly one day, so
/// *announce once, ever* is reached by a date comparison rather than by remembering — the same move
/// ADR-0016 made on finding the due check was a state comparison and not a calendar event. Running
/// this twice on the same day sends the same notification twice, which is a scheduling question and
/// deliberately not a state one.
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyPushService {

    /// Three strings out of a record, the same way `MapToJsonConverter` does it: a mapper with no
    /// configuration to inherit and nothing to inject.
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final DueTasks dueTasks;
    private final PushSubscriptionService subscriptions;
    private final WebPushClient webPushClient;
    private final Clock clock;

    public void pushWhatIsDueToday() {
        var today = LocalDate.now(clock);
        var digest = DailyDigest.of(dueTasks.namesOfTasksDueOn(today));

        if (digest.isEmpty()) {
            // Silent, and logged as silence: ADR-0009 gives the log a 30-day forensic window, and
            // "nothing was due" is the one thing the tasks themselves already say.
            log.debug("Nothing is due on {}; no notification sent.", today);
            return;
        }

        var devices = subscriptions.all();
        if (devices.isEmpty()) {
            return;
        }

        // The envelope, never the record: `ngsw-worker.js` shows nothing for a payload without a
        // `notification` object, and every layer below this one would still report success. See
        // `DailyDigest#asServiceWorkerPayload`.
        var payload = MAPPER.writeValueAsString(digest.get().asServiceWorkerPayload())
                .getBytes(StandardCharsets.UTF_8);
        var delivered = 0;
        for (PushSubscription device : devices) {
            if (send(device, payload) == PushOutcome.DELIVERED) {
                delivered++;
            }
        }
        log.info("Notified {} of {} device(s) about {} task(s) due on {}.",
                delivered, devices.size(), digest.get().body(), today);
    }

    /// One device's failure is one device's failure — `DueTemplateChecker`'s rule, for the same
    /// reason: without it, a phone whose push service is having a bad morning stops the tablet from
    /// being told anything at all.
    private PushOutcome send(PushSubscription device, byte[] payload) {
        try {
            var outcome = webPushClient.send(device.target(), payload);
            if (outcome == PushOutcome.GONE) {
                subscriptions.forget(device);
            }
            return outcome;
        } catch (RuntimeException e) {
            log.error("Could not notify device {}.", device.id(), e);
            return PushOutcome.FAILED;
        }
    }

}
