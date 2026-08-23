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

    /// Starts a new lineage of history, and is only ever called **in the same transaction as the
    /// thing that rewound the sequence** — never after it. Between two separate commits the server
    /// hands out numbers it has already issued while the epoch still promises they are unique, which
    /// is exactly the silent divergence ADR-0004's epoch exists to prevent.
    ///
    /// `restore.sh` does the same `UPDATE` from outside the application (ADR-0008, step four); this
    /// is the in-process path, for [the importer](be.stijnhooft.task.backend.task.TaskImport), whose
    /// truncate-and-reload is the other operation that starts a lineage
    /// ([#72](https://github.com/stainii/task/issues/72)).
    ///
    /// The cache is written before the transaction commits, so a rollback leaves this process
    /// believing the epoch is one higher than it is. That is the harmless direction: a too-high
    /// epoch answers clients with a resync they did not need, while a too-low one is the permanent
    /// silence. The next [#refresh] — one per client connection — corrects it either way.
    public long bump() {
        var epoch = jdbcClient.sql("UPDATE sync_epoch SET epoch = epoch + 1 WHERE id = 1 RETURNING epoch")
                .query(Long.class)
                .single();
        current = epoch;
        return epoch;
    }

    public long refresh() {
        var epoch = jdbcClient.sql("SELECT epoch FROM sync_epoch WHERE id = 1")
                .query(Long.class)
                .single();
        current = epoch;
        return epoch;
    }
}
