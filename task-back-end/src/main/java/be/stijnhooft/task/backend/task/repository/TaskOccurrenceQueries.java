package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.task.domain.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/// The three questions `TaskOccurrences` answers, as SQL over the task table.
///
/// Aggregates rather than rows, so they are read with `JdbcClient` the way the sequence's watermark
/// is: loading a template's whole history to take a maximum from it would grow without bound and
/// answer exactly the same thing.
///
/// **Closed is written as *not open*.** Naming `COMPLETED` and `CANCELLED` instead would make a
/// fourth status silently count as open - and it is the closure, not which kind of closure, that
/// these queries are about.
@Repository
@RequiredArgsConstructor
public class TaskOccurrenceQueries {

    private final JdbcClient jdbcClient;

    public boolean hasOpenTask(UUID templateId) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("SELECT EXISTS(SELECT 1 FROM task WHERE task_template_id = :templateId AND status = :status)")
                .param("templateId", templateId)
                .param("status", TaskStatus.OPEN.name())
                .query(Boolean.class)
                .single());
    }

    /// The latest day work actually happened. `completed_on` is null on anything that was never
    /// completed, so the status filter says what is meant rather than relying on that.
    public Optional<LocalDate> latestCompletedOn(UUID templateId) {
        return jdbcClient
                .sql("SELECT MAX(completed_on) FROM task WHERE task_template_id = :templateId AND status = :status")
                .param("templateId", templateId)
                .param("status", TaskStatus.COMPLETED.name())
                .query(LocalDate.class)
                .optional();
    }

    /// The latest firing date among the template's closed tasks - the firing date being the task's
    /// creation date.
    public Optional<Instant> latestClosedFiring(UUID templateId) {
        return jdbcClient
                .sql("SELECT MAX(creation_date_time) FROM task WHERE task_template_id = :templateId AND status <> :status")
                .param("templateId", templateId)
                .param("status", TaskStatus.OPEN.name())
                .query(Instant.class)
                .optional();
    }
}
