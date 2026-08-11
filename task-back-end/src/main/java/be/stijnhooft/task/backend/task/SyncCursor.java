package be.stijnhooft.task.backend.task;

import org.jspecify.annotations.Nullable;

/// Where a client has got to: which lineage of history it is on, and how far along it.
///
/// **Both halves travel together, always.** [ADR-0004](../../../../../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md)
/// made the SSE event id the `sequence`, so the browser maintains the cursor for free across a
/// reconnect - and separately made clients "persist the epoch alongside their cursor and present
/// both on every reconnect". Those two rules do not compose: on the reconnect the browser performs
/// *by itself*, the only thing it sends is `Last-Event-ID`, so a bare sequence there is a cursor
/// with no lineage. The epoch check would then run on the deliberate reconnect and be skipped on
/// the automatic one - which is the path that runs after every bounded-lifetime close, and so the
/// path that matters. The event id therefore carries both, as `epoch:sequence`.
public record SyncCursor(long epoch, long sequence) {

    /// Parses an `epoch:sequence` id, or null if it is not one.
    ///
    /// Null means *unservable*, not *absent*: a client that presents a cursor the server cannot
    /// read has state the server cannot account for, so it is resynced rather than quietly given a
    /// live tail with a hole in front of it.
    public static @Nullable SyncCursor parse(String raw) {
        var separator = raw.indexOf(':');
        if (separator < 0) {
            return null;
        }
        try {
            return new SyncCursor(
                    Long.parseLong(raw.substring(0, separator)),
                    Long.parseLong(raw.substring(separator + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String format() {
        return epoch + ":" + sequence;
    }
}
