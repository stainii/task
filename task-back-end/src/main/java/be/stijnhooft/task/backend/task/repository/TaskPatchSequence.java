package be.stijnhooft.task.backend.task.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/// The server's delivery clock: one monotonic number per patch, assigned on receipt.
///
/// A database sequence rather than a column default, because Spring Data JDBC writes every mapped
/// column explicitly and would insert the null it was handed. So the number is minted here, where
/// forgetting it is a not-null violation on the very first write rather than a cursor that quietly
/// stops advancing.
@Repository
@RequiredArgsConstructor
public class TaskPatchSequence {

    private final JdbcClient jdbcClient;

    public long next() {
        return jdbcClient.sql("SELECT nextval('task_patch_sequence')")
                .query(Long.class)
                .single();
    }

    /// The highest sequence a client could have been told about: the cursor a snapshot was read at.
    ///
    /// Taken from the stored patches rather than from the sequence's `last_value`, because a number
    /// can be minted and its transaction rolled back. A watermark ahead of what was actually stored
    /// would make the client ask for patches that will never exist, and #46 answers an unservable
    /// cursor with a resync - a hard reset triggered by our own arithmetic.
    public long watermark() {
        return jdbcClient.sql("SELECT COALESCE(MAX(sequence), 0) FROM task_patch")
                .query(Long.class)
                .single();
    }
}
