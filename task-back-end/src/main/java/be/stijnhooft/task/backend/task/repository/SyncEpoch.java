package be.stijnhooft.task.backend.task.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/// Which lineage of history this server is on (ADR-0004's epoch, added by ADR-0007).
///
/// Cached rather than queried per emitted patch, and **refreshed whenever a client connects**. A
/// restore bumps the row from outside the application, which normally means a restart - but a bump
/// the running process never notices is a bump that changes nothing, and the failure would be
/// exactly the silent one the epoch exists to prevent. Connections are bounded to twenty minutes
/// (ADR-0004), so refreshing on connect puts a hard ceiling on how long a stale epoch can be served.
@Repository
@RequiredArgsConstructor
public class SyncEpoch {

    private static final long UNREAD = 0L;

    private final JdbcClient jdbcClient;

    private volatile long current = UNREAD;

    /// Read lazily rather than in the constructor: nothing orders bean creation after Flyway, and
    /// a repository that queries its own table while being built would depend on that ordering.
    public long current() {
        var cached = current;
        return cached == UNREAD ? refresh() : cached;
    }

    public long refresh() {
        var epoch = jdbcClient.sql("SELECT epoch FROM sync_epoch WHERE id = 1")
                .query(Long.class)
                .single();
        current = epoch;
        return epoch;
    }
}
