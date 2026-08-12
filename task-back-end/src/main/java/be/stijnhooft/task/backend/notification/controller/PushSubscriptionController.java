package be.stijnhooft.task.backend.notification.controller;

import be.stijnhooft.task.backend.notification.dto.PushSubscriptionDto;
import be.stijnhooft.task.backend.notification.dto.PushSubscriptionRemovalDto;
import be.stijnhooft.task.backend.notification.service.PushSubscriptionService;
import be.stijnhooft.task.backend.notification.webpush.VapidKeys;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/// Registering a device, forgetting a device, and the one key the browser needs to do either.
///
/// All three are `/api`, so all three require the realm role `task-user` like everything else
/// ([ADR-0010](../../../../../../../../docs/adr/0010-a-tunnel-an-allowlist-and-a-role.md)) — the
/// application server key included, because a personal app has no reason to publish it to anyone
/// who asks.
///
/// **Two `POST`s and no `DELETE`.** Removal names an endpoint, and an endpoint is a long URL that
/// has no business in a path segment; `POST /{id}/deactivation` set the precedent in
/// [#50](https://github.com/stainii/task/issues/50) for a state change that is not a resource.
/// Every mapping names its verb and none carries a trailing slash (`docs/quality-bar.md` §6, D4).
///
/// Neither write is online-only by accident: a subscription **is** a fact about the browser's
/// current connection to a push service, so there is nothing to queue offline the way ADR-0004
/// queues a patch.
@RestController
@RequestMapping("/api/push-subscriptions")
@RequiredArgsConstructor
public class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;
    private final VapidKeys vapidKeys;

    /// What the client passes as `applicationServerKey` to `pushManager.subscribe`. Public by
    /// nature — it is the half of the VAPID pair that ends up in every subscription — and read from
    /// the running configuration rather than duplicated into the front-end bundle, so a rotated key
    /// cannot leave a client subscribing against the old one.
    @GetMapping("/application-server-key")
    public String applicationServerKey() {
        return vapidKeys.publicKeyAsString();
    }

    /// **Idempotent**: the client re-registers on every app open (ADR-0012's repair mechanism), so
    /// this is called far more often than a device is added. See `PushSubscriptionService#register`.
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@Valid @RequestBody PushSubscriptionDto subscription) {
        pushSubscriptionService.register(
                subscription.endpoint(),
                subscription.keys().p256dh(),
                subscription.keys().auth());
    }

    /// Turning the toggle off. Also idempotent — a device already forgotten is in the state the
    /// caller asked for, and a `404` here would make a second tap look like a failure.
    @PostMapping("/removal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@Valid @RequestBody PushSubscriptionRemovalDto removal) {
        pushSubscriptionService.forget(removal.endpoint());
    }
}
