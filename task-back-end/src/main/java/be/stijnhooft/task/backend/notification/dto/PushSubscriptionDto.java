package be.stijnhooft.task.backend.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/// **The browser's own shape**, not ours: this is exactly what `PushSubscription.toJSON()` returns
/// in the client, so the front-end posts what the `PushManager` handed it and never re-assembles it.
///
/// Re-assembling is where this goes wrong — `keys.p256dh` and `keys.auth` are opaque base64url
/// strings, and a client that flattens them itself is one refactor away from swapping them, which
/// produces a subscription that registers cleanly and can never be decrypted on the device.
public record PushSubscriptionDto(

        @NotBlank String endpoint,

        @NotNull @Valid Keys keys) {

    public record Keys(@NotBlank String p256dh, @NotBlank String auth) {
    }
}
