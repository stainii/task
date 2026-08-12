package be.stijnhooft.task.backend.notification.webpush;

/// One device, exactly as the browser describes it: where to post, and the two secrets that make the
/// message readable only there.
///
/// It is what `PushSubscription` is *for*, without being `PushSubscription` - the crypto has no
/// business knowing there is a row, an id or a database.
public record PushTarget(String endpoint, String p256dh, String auth) {
}
