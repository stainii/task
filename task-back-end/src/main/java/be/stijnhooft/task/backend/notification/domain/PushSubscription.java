package be.stijnhooft.task.backend.notification.domain;

import be.stijnhooft.task.backend.notification.webpush.PushTarget;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

import java.time.Instant;
import java.util.UUID;

/// **One device's registration to receive the daily notification** — and the only thing this
/// application persists about notifications at all
/// ([ADR-0012](../../../../../../../../docs/adr/0012-one-push-at-0730-derived-not-stored.md)).
///
/// There is no notification entity, no inbox, no read state and no `published` flag. Portal needed
/// all of that because a notification was a message *in flight* between two services over RabbitMQ;
/// inside one deployable a notification is a projection of the tasks due that day, computed at 07:30
/// and not remembered. Portal's 8,201 rows bought a 4% read rate.
///
/// A record, like every other entity here since [#45](https://github.com/stainii/task/issues/45):
/// no Lombok builder, and therefore no `@Builder.Default` hole for Error Prone to miss
/// (`docs/quality-bar.md` §2).
///
/// ### The endpoint is the identity, the id is bookkeeping
///
/// The browser mints the endpoint and re-mints it whenever Chrome rotates the subscription. The
/// client re-registers on **every** app open, so registration has to be idempotent on `endpoint` or
/// one device accumulates a row a day — see `PushSubscriptionService`.
public record PushSubscription(

        @Id UUID id,

        /// Where to `POST`, chosen by the browser: `fcm.googleapis.com`, `updates.push.services.mozilla.com`
        /// and so on. Unique — see the class comment.
        String endpoint,

        /// The device's P-256 public key, base64url, uncompressed. Half of what makes the message
        /// readable on that device and nowhere else.
        String p256dh,

        /// The subscription's shared auth secret, base64url. The other half.
        String auth,

        /// When this device registered. Not used to decide anything — kept because a subscription
        /// list with no dates cannot answer *"is this the phone I replaced in March?"*, and there is
        /// no other record of a device anywhere in the system.
        Instant registeredOn,

        @Version long version) {

    public static PushSubscription of(UUID id, String endpoint, String p256dh, String auth, Instant registeredOn) {
        return new PushSubscription(id, endpoint, p256dh, auth, registeredOn, 0);
    }

    /// What the crypto needs, without handing it a row.
    public PushTarget target() {
        return new PushTarget(endpoint, p256dh, auth);
    }

    /// A re-registration of the same endpoint whose keys have moved. Chrome rotates keys without
    /// changing the endpoint, so the row is updated rather than replaced — replacing it would give
    /// the same device a new id every morning.
    public PushSubscription withKeys(String p256dh, String auth) {
        return new PushSubscription(id, endpoint, p256dh, auth, registeredOn, version);
    }
}
