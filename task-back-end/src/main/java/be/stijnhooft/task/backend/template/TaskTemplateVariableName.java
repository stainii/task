package be.stijnhooft.task.backend.template;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("task_template_variable_name")
public record TaskTemplateVariableName(
        @Column("variable_name") String variableName
) {
}
