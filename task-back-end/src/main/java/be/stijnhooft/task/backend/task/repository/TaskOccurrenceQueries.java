package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.task.domain.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/// The four questions `TaskOccurrences` answers, as SQL over the task table.
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

    /// Any task at all, whatever its status. Deletion is refused on history, not on open work.
    public boolean hasAnyTask(UUID templateId) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("SELECT EXISTS(SELECT 1 FROM task WHERE task_template_id = :templateId)")
                .param("templateId", templateId)
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

    /// The template's most recently closed task, as **the day it fired and the day it closed**
    /// (ADR-0022). One row rather than two independent maxima, because the two dates belong to one
    /// task and a scheduler reading half of each would be counting from a round that never existed.
    ///
    /// *Most recently* is by the closure date: the newest round to have ended. Ties fall to the
    /// later firing date, which is what a multi-definition firing closed in one go produces.
    ///
    /// The closure date is `completed_on` or `cancelled_on`, and falls back to the firing date when
    /// a closed task carries neither. That is **not** the fallback ADR-0022 rejected — V9 backfills
    /// every existing cancellation, so nothing reaches this by being old. It is the floor for a
    /// malformed write: a client may `PATCH` `status` without a date beside it, and the honest
    /// answer to a closure with no closure date is today's behaviour rather than a template that
    /// throws once an hour for ever.
    public Optional<ClosedTask> latestClosure(UUID templateId) {
        return jdbcClient
                .sql("""
                        SELECT creation_date_time, COALESCE(completed_on, cancelled_on) AS closed_on
                        FROM task
                        WHERE task_template_id = :templateId AND status <> :status
                        ORDER BY closed_on DESC NULLS LAST, creation_date_time DESC
                        LIMIT 1
                        """)
                .param("templateId", templateId)
                .param("status", TaskStatus.OPEN.name())
                // Through `OffsetDateTime` and not straight to `Instant`: pgjdbc refuses the latter
                // for a `timestamptz` ("conversion to class java.time.Instant from timestamptz not
                // supported"), and the failure is a per-template ERROR line in the hourly sweep
                // rather than anything a caller sees.
                .query((rs, rowNum) -> new ClosedTask(
                        rs.getObject("creation_date_time", OffsetDateTime.class).toInstant(),
                        rs.getObject("closed_on", LocalDate.class)))
                .optional();
    }

    /// One closed task's two dates, still in the shapes the table holds them in: a firing date is an
    /// instant here and a day to its reader, and only the `Clock` bean owns the zone between them.
    public record ClosedTask(Instant firedAt, @Nullable LocalDate closedOn) {
    }
}
