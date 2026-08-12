package be.stijnhooft.task.backend.template.service;

import be.stijnhooft.task.backend.template.TaskTemplateImport;
import be.stijnhooft.task.backend.template.domain.TaskDefinition;
import be.stijnhooft.task.backend.template.domain.TaskTemplate;
import be.stijnhooft.task.backend.template.domain.Trigger;
import be.stijnhooft.task.backend.template.repository.TaskTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/// `template`'s half of the portal import
/// ([ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)).
///
/// It calls `validateForSaving()` like every other write path does, which is not a formality here:
/// portal's three `taskTemplate` documents are full of `${…}`, and the rule that placeholders are
/// **manual-only** is exactly what makes them legal. A migrated template that failed this check
/// would be one the authoring screen could never have produced.
@Service
@RequiredArgsConstructor
public class TaskTemplateImportService implements TaskTemplateImport {

    private final TaskTemplateRepository taskTemplateRepository;
    private final JdbcClient jdbcClient;

    @Override
    @Transactional
    public void deleteAllTemplates() {
        jdbcClient.sql("TRUNCATE TABLE task_definition, task_template CASCADE").update();
    }

    @Override
    @Transactional
    public void importTemplate(ImportedTemplate imported) {
        var definitions = imported.definitions().stream()
                .map(definition -> new TaskDefinition(
                        definition.id(),
                        definition.name(),
                        definition.startDateOffsetDays(),
                        definition.dueDateOffsetDays(),
                        definition.importance(),
                        definition.description()))
                .toList();

        var template = TaskTemplate.of(
                imported.id(),
                imported.name(),
                imported.context(),
                imported.activeSince(),
                toTrigger(imported.trigger()),
                definitions);

        template.validateForSaving();
        taskTemplateRepository.save(template);
    }

    private static Trigger toTrigger(ImportedTrigger trigger) {
        return switch (trigger) {
            case ImportedTrigger.Manual manual -> new Trigger.Manual(manual.anchorLabel());
            case ImportedTrigger.MinMax minMax -> new Trigger.MinMax(minMax.min(), minMax.max());
        };
    }

    @Override
    public long templateCount() {
        return count("task_template");
    }

    @Override
    public long definitionCount() {
        return count("task_definition");
    }

    private long count(String table) {
        // Two literals chosen here, never anything a caller supplies.
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
