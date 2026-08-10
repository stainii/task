package be.stijnhooft.task.backend.recurring;

import be.stijnhooft.task.backend.task.Importance;
import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table("recurring_task_template")
/// Parked by #10 (docs/quality-bar.md). JavaPeriodGetDays here is DEFECT D1, not noise:
/// Period.getDays() reads only the day component, so a template with a min interval over
/// a month never becomes due. It is suppressed rather than fixed because ADR-0001 deletes
/// this class outright, replacing it with TaskTemplate and a sealed Trigger. If this class
/// is still here when you read this, D1 is still live: see docs/repo-health.md.
/// NullAway: Lombok @Builder gives no constructor that proves the non-null fields are set.
@SuppressWarnings({"NullAway", "JavaPeriodGetDays"})
public class RecurringTaskTemplate {

    @Id
    private UUID id;

    private String name;

    /**
     * The minimum number of days between each execution of this task.
     * It's not necessary to execute this task more regularly.
     **/
    private int minNumberOfDaysBetweenExecutions;

    /**
     * The maximum number of days between each execution of this task.
     * If this value gets exceeded, a final warning will be sent.
     **/
    private int maxNumberOfDaysBetweenExecutions;

    @MappedCollection(idColumn = "recurring_task_id", keyColumn = "index")
    @Builder.Default
    private List<Execution> executions = new ArrayList<>();

    /// Set by whoever creates the template, from the Clock bean (#44): an entity does not read
    /// the clock. ADR-0017 replaces this field with `active_since` when #47 rebuilds the class.
    private LocalDate creationDate;

    private boolean activeTask;

    @Nullable
    private Importance importance;

    private String context;

    @Nullable
    private String description;

    @Version
    private long version;

    public RecurringTaskTemplate(UUID id, String name, int minNumberOfDaysBetweenExecutions, int maxNumberOfDaysBetweenExecutions) {
        checkData(name, minNumberOfDaysBetweenExecutions, maxNumberOfDaysBetweenExecutions);
        this.id = id;
        this.name = name;
        this.minNumberOfDaysBetweenExecutions = minNumberOfDaysBetweenExecutions;
        this.maxNumberOfDaysBetweenExecutions = maxNumberOfDaysBetweenExecutions;
    }

    public List<Execution> getExecutions() {
        return Collections.unmodifiableList(executions);
    }

    public void addExecution(Execution execution) {
        var executions = new ArrayList<>(this.executions);
        executions.add(execution);
        this.executions = executions; // why... Spring Boot Data JDBC needs this to trigger the update query. Otherwise, it thinks the collection is unchanged and doesn't update the database. Spring Data JPA doesn't have this issue, but we want to use Spring Data JDBC for simplicity and performance.
    }


    public void update(String name, int minNumberOfDaysBetweenExecutions, int maxNumberOfDaysBetweenExecutions) {
        checkData(name, minNumberOfDaysBetweenExecutions, maxNumberOfDaysBetweenExecutions);
        this.name = name;
        this.minNumberOfDaysBetweenExecutions = minNumberOfDaysBetweenExecutions;
        this.maxNumberOfDaysBetweenExecutions = maxNumberOfDaysBetweenExecutions;
    }

    private void checkData(String name, int minNumberOfDaysBetweenExecutions, int maxNumberOfDaysBetweenExecutions) {
        if (minNumberOfDaysBetweenExecutions <= 0
                || maxNumberOfDaysBetweenExecutions <= 0) {
            throw new IllegalArgumentException("Task %s: The number of days between executions need to be greater than 0. Min: %s, max: %s".formatted(name, minNumberOfDaysBetweenExecutions, maxNumberOfDaysBetweenExecutions));
        }
        if (maxNumberOfDaysBetweenExecutions < minNumberOfDaysBetweenExecutions) {
            throw new IllegalArgumentException("Task %s: The maximum number of days between executions cannot be smaller than the minimum. Min: %s, max: %s".formatted(name, minNumberOfDaysBetweenExecutions, maxNumberOfDaysBetweenExecutions));
        }
    }

    public boolean shouldTaskBeCreatedBecauseItIsDue(LocalDate now) {
        var numberOfDaysSinceLastExecution = Period.between(getLastExecutionDateOrCreationDate(), now).getDays();
        var isDue = numberOfDaysSinceLastExecution >= minNumberOfDaysBetweenExecutions;
        return !activeTask && isDue;
    }

    public LocalDate getLastExecutionDateOrCreationDate() {
        return executions.stream()
                .map(Execution::getDate)
                .max(LocalDate::compareTo)
                .orElse(creationDate);
    }

}
