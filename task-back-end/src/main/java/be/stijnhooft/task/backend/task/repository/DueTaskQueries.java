package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.domain.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/// *What is open and due on this date?* — the one question `DueTasks` answers, as SQL over the task
/// table.
///
/// Two columns and no aggregate, because the whole result is one morning's work: [#35](https://github.com/stainii/task/issues/35)
/// measured **28 open tasks in the entire live system**, so this is a handful of rows a day.
@Repository
@RequiredArgsConstructor
public class DueTaskQueries {

    private final JdbcClient jdbcClient;

    /// Ordering is deliberately **not** done here. Which name gets dropped into *"+2 more"* is a
    /// domain rule about importance, and `Importance` is a Java enum: a `CASE` over its values in
    /// SQL would silently sort a newly added value last, in a file nobody would think to open.
    public List<DueTask> openTasksDueOn(LocalDate date) {
        return jdbcClient
                .sql("SELECT name, importance FROM task WHERE status = :status AND due_date = :date")
                .param("status", TaskStatus.OPEN.name())
                .param("date", date)
                .query(DueTask.class)
                .list();
    }

    public record DueTask(String name, Importance importance) {
    }
}
