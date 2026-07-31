package be.stijnhooft.task.backend.template;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// A template that can be used to create tasks ([be.stijnhooft.task.backend.task.Task]).
/// This is useful if the creation of a multiple tasks, related to one goal.
/// A template consists of one or more task definitions. For each task definition, one task will be created.
/// The task definitions describes what its task should look like.
@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor
@EqualsAndHashCode(exclude = "version")
@Table("task_template")
public class TaskTemplate {

    @Id
    private UUID id;

    private String name;

    @MappedCollection(idColumn = "task_template_id", keyColumn = "index")
    private List<TaskDefinition> taskDefinitions = new ArrayList<>();

    /// Names of variables that need to be replaced in certain attributes of the class.
    ///
    /// @see TaskDefinition#getName()
    /// @see TaskDefinition#getContext()
    /// @see TaskDefinition#getDescription()
    @MappedCollection(idColumn = "task_template_id", keyColumn = "index")
    private List<TaskTemplateVariableName> variableNames = new ArrayList<>();

    @Version
    private long version;

}
