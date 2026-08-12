package be.stijnhooft.task.backend.notification.service;

import be.stijnhooft.task.backend.notification.domain.PushSubscription;
import be.stijnhooft.task.backend.notification.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

/// The register of devices: one row per device, all of them pushed to
/// ([ADR-0012](../../../../../../../../docs/adr/0012-one-push-at-0730-derived-not-stored.md) — no
/// primary device, no dedup, nothing to reconcile at one notification a day).
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final Clock clock;

    /// **Registration is idempotent on the endpoint, and that is a requirement rather than a
    /// nicety.** ADR-0012 has the client re-read its subscription on *every* app open and re-send
    /// it; a plain insert would give one phone a row per launch and one notification per row.
    ///
    /// It is also [#28](https://github.com/stainii/task/issues/28)'s lesson applied before it bites:
    /// there, a client-minted id retried after a lost response hit a primary-key violation, which
    /// ADR-0004's outbox turned into a permanently wedged queue. A repeat of a write that already
    /// succeeded is a `200`, everywhere.
    ///
    /// Chrome may rotate a subscription's **keys** without changing its endpoint, so a re-register
    /// updates the row rather than ignoring it — silently keeping stale keys would leave the device
    /// registered and unreachable, which is exactly the failure this feature is meant not to have.
    public PushSubscription register(String endpoint, String p256dh, String auth) {
        return repository.findByEndpoint(endpoint)
                .map(existing -> existing.p256dh().equals(p256dh) && existing.auth().equals(auth)
                        ? existing
                        : repository.save(existing.withKeys(p256dh, auth)))
                .orElseGet(() -> repository.save(
                        PushSubscription.of(UUID.randomUUID(), endpoint, p256dh, auth, clock.instant())));
    }

    /// Turning the toggle off on a device. Idempotent for the same reason registration is: a device
    /// that has already been forgotten is in the state the caller asked for.
    public void forget(String endpoint) {
        repository.findByEndpoint(endpoint).ifPresent(repository::delete);
    }

    /// **The server's one job in keeping the channel healthy** (ADR-0012): a dead endpoint is
    /// garbage, and nothing is reported to anyone. A banner would be unreachable — it could only be
    /// seen when the app is open, which is precisely when the client has already re-subscribed.
    public void forget(PushSubscription subscription) {
        repository.delete(subscription);
        log.info("Push subscription {} was gone at the push service and has been deleted.", subscription.id());
    }

    @Transactional(readOnly = true)
    public List<PushSubscription> all() {
        return StreamSupport.stream(repository.findAll().spliterator(), false).toList();
    }
}
