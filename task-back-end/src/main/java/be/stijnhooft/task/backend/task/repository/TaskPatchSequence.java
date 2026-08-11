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
}
