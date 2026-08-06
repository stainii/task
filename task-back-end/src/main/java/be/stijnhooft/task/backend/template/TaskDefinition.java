package be.stijnhooft.task.backend.template;

import be.stijnhooft.task.backend.task.Importance;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * The definition of one task in a task template.
 *
 * @see TaskTemplate for more information.
 */
@Data
@Table("task_definition")
@NoArgsConstructor
@AllArgsConstructor
/// Parked by #10 (docs/quality-bar.md): Lombok @NoArgsConstructor leaves NullAway unable to
/// prove the non-null fields are initialised. Resolved when the entity is rebuilt.
@SuppressWarnings("NullAway")
public class TaskDefinition {

    @Id
    private UUID id;

    /// Can contain variable names. The variable names need to be defined in attribute [TaskTemplate#getVariableNames()].
    /// Example: "Hello, ${user}!"
    private String name;

    /**
     * Used to calculate the start date of this task, compared to the start date/due date of the main task.
     * <p>
     * Example: when task A can be started, a week later task B can be picked up.
     * The deviation days of task A, the main task, is 0.
     * The deviation days of task B, the sub task, is 7. The deviation base is due date.
     * => The start date of task B will be 7 days later than the due date of task A.
     * <p>
     * If null, no start date will be set for the resulting task.
     */
    private Integer startDateDeviationDays;

    private DeviationBase startDateDeviationBase;

    /// Used to calculate the due date of this task, compared to the due date of the main task.
    /// Useful for subtasks.
    ///
    /// Example: when task A has to be done, a week later task B should be picked up.
    /// The deviation of task A, the main task, is 0.
    /// The deviation of task B, the sub task, is 7. The deviation base is due date.
    /// => The start date of task B will be 7 days later than the due date of task A.
    ///
    /// If null, no due date will be set for the resulting task.
    private Integer dueDateDeviationDays;
    private DeviationBase dueDateDeviationBase;

    /// Can contain variable names. The variable names need to be defined in attribute [TaskTemplate#getVariableNames()].
    ///
    /// Example: "Hello, ${user}!"
    @NonNull
    private String context;

    private Importance importance;

    /// Can contain variable names. The variable names need to be defined in attribute [TaskTemplate#getVariableNames()].
    ///
    /// Example: "Hello, ${user}!"
    private String description;

}
