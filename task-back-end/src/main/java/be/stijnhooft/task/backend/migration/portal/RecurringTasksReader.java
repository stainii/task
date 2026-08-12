package be.stijnhooft.task.backend.migration.portal;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/// Reads one of portal's four `portal-recurring-tasks` databases — housagotchi, setlist, health and
/// social-recurring-tasks, each the same Liquibase changelog deployed four times.
///
/// The whole schema is two tables:
///
/// ```
/// recurring_task(id, name, min_number_of_days_between_executions, max_number_of_days_between_executions)
/// execution(id, date, recurring_task_id)
/// ```
///
/// `execution.date` is a `timestamp without time zone` and is read as a **date**. Portal only ever
/// recorded which day something was done — the time component is an artefact of the column type, not
/// a fact — so unlike a patch's `startDateTime` there is no zone question here and nothing to
/// narrow.
public class RecurringTasksReader {

    private final String deployment;
    private final JdbcClient jdbcClient;

    public RecurringTasksReader(String deployment, JdbcClient jdbcClient) {
        this.deployment = deployment;
        this.jdbcClient = jdbcClient;
    }

    public String deployment() {
        return deployment;
    }

    public List<PortalArchive.PortalRecurringTask> recurringTasks() {
        return jdbcClient.sql("""
                        SELECT id,
                               name,
                               min_number_of_days_between_executions AS min_days,
                               max_number_of_days_between_executions AS max_days
                        FROM recurring_task
                        ORDER BY id
                        """)
                .query((rs, rowNum) -> new PortalArchive.PortalRecurringTask(
                        deployment,
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("min_days"),
                        rs.getInt("max_days")))
                .list();
    }

    /// Executions with no `recurring_task_id` are skipped by the query rather than carried and
    /// dropped later: an execution names *which chore was done*, and one that names none is not a
    /// fact anything can be built from.
    public List<PortalArchive.PortalExecution> executions() {
        return jdbcClient.sql("""
                        SELECT id, date, recurring_task_id
                        FROM execution
                        WHERE recurring_task_id IS NOT NULL
                        ORDER BY id
                        """)
                .query((rs, rowNum) -> new PortalArchive.PortalExecution(
                        deployment,
                        rs.getLong("id"),
                        rs.getLong("recurring_task_id"),
                        toDate(rs.getObject("date", LocalDateTime.class))))
                .list();
    }

    private static LocalDate toDate(LocalDateTime dateTime) {
        return dateTime.toLocalDate();
    }
}
