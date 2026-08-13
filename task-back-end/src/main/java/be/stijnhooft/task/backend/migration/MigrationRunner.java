package be.stijnhooft.task.backend.migration;

import be.stijnhooft.task.backend.migration.portal.RecurringTasksReader;
import be.stijnhooft.task.backend.migration.portal.TodoReader;
import be.stijnhooft.task.backend.task.TaskImport;
import be.stijnhooft.task.backend.template.TaskTemplateImport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/// Runs the import once, on startup, and only when someone asked for it.
///
/// **Gated on the `migration` profile**, which is never active on the server. ADR-0007 deploys every
/// green push to `main` with no staging, and this thing truncates the task table — so the one thing
/// that must be impossible is it running because a property was defaulted somewhere. Without the
/// profile the module is not instantiated, connects to nothing, and the Mongo driver it carries is
/// dead weight in the image and nothing else.
///
/// Invoked as:
///
/// ```
/// ./mvnw spring-boot:run -Dspring-boot.run.profiles=migration
/// ```
@Slf4j
@Configuration
@Profile("migration")
@EnableConfigurationProperties(MigrationProperties.class)
@RequiredArgsConstructor
public class MigrationRunner implements ApplicationRunner {

    private final MigrationProperties properties;
    private final TaskImport taskImport;
    private final TaskTemplateImport taskTemplateImport;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        log.warn("Running the one-shot portal import. This TRUNCATES tasks, patches and templates.");

        var deployments = new ArrayList<RecurringTasksReader>();
        for (var deployment : properties.deployments()) {
            var dataSource = new DriverManagerDataSource(
                    deployment.url(), deployment.username(), deployment.password());
            deployments.add(new RecurringTasksReader(deployment.name(), JdbcClient.create(dataSource)));
        }

        try (var todo = new TodoReader(properties.mongoUri(), properties.mongoDatabase())) {
            var report = new PortalImporter(taskImport, taskTemplateImport, clock)
                    .importFrom(todo, List.copyOf(deployments));
            log.info("\n{}", report.render());
            write(report);
        }
    }

    /// Writes the report beside the archive, timestamped so a re-run never overwrites the run it is
    /// being compared against — the fix loop is *read, change the mapping, run again, diff the two*,
    /// and it does not work if the first one is gone.
    ///
    /// A failure to write is logged and swallowed. The import itself has already succeeded and the
    /// summary is already on the console; throwing here would make an unwritable directory look
    /// like a failed migration, which is the more expensive misunderstanding.
    private void write(ImportReport report) {
        var stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss").withZone(clock.getZone()).format(clock.instant());
        try {
            var directory = Files.createDirectories(properties.reportDirectory());
            var prose = directory.resolve("import-report-" + stamp + ".txt");
            var sidecar = directory.resolve("import-differences-" + stamp + ".csv");
            Files.writeString(prose, report.render());
            Files.writeString(sidecar, report.renderCsv());
            log.warn("Report written to {} and {}. Both quote real task names - they are outside the "
                    + "repository on purpose (#31) and must stay there.", prose, sidecar);
        } catch (IOException cannotWrite) {
            log.error("The import succeeded but its report could not be written to {}. The summary "
                    + "above is all there is; re-run once the directory is writable.",
                    properties.reportDirectory(), cannotWrite);
        }
    }
}
