package be.stijnhooft.task.backend.notification.dto;

import jakarta.validation.constraints.NotBlank;

/// Turning the toggle off names the endpoint, because the endpoint is what the device knows about
/// itself. Our row id is never handed out, so it cannot be handed back.
public record PushSubscriptionRemovalDto(@NotBlank String endpoint) {
}
