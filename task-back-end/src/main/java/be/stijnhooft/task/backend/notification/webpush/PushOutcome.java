package be.stijnhooft.task.backend.notification.webpush;

/// What became of one send, in the only three flavours anything acts on.
///
/// **`GONE` is the whole point of this enum.** ADR-0012 gives the server exactly one job in keeping
/// the channel healthy - delete a subscription the push service says is dead - and everything else
/// is the client's re-subscribe-on-open. Folding `410` in with the other failures would leave dead
/// rows accumulating and pushed to every morning for ever.
public enum PushOutcome {

    /// The push service accepted it. It says nothing about the phone: a device that is off, or has
    /// the app uninstalled, accepts today and answers `410` some later day.
    DELIVERED,

    /// `404`/`410` - this subscription no longer exists. **Delete the row.** Chrome rotates
    /// endpoints and clearing browser data kills them, so this is the ordinary path, not an error.
    GONE,

    /// Anything else: a `5xx` from the push service, a timeout, a network that was not there. The
    /// subscription is kept and tomorrow's push tries again - there is no retry today, because the
    /// message is *what is due today* and a message that arrives at 22:00 is worse than none.
    FAILED
}
