package be.stijnhooft.task.backend.migration;

import be.stijnhooft.task.backend.ComposeFile;
import be.stijnhooft.task.backend.task.TaskImport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// **The importer starts a new lineage of history, so it moves the epoch**
/// ([#72](https://github.com/stainii/task/issues/72),
/// [ADR-0004](../../../../../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md)'s
/// epoch amendment).
///
/// This is the boundary `deploy/restore.sh` gets for free from the nightly restore drill and the
/// import path had nothing equivalent to. The failure it guards has **no symptom at the time**:
/// tasks are there, the app comes up, and weeks later two devices quietly disagree — so nothing but
/// an assertion can notice it.
///
/// ### Why it is not folded into `PortalArchiveImportIntegrationTest`
///
/// That class is the dry run against the author's real archive and is **skipped wherever the
/// archive is not restored**, which is everywhere except one machine. The epoch step needs no
/// corpus — it is true of an empty database — and putting it there would mean CI never runs the one
/// assertion standing between a partial import and permanent silent divergence.
///
/// ### Why it has its own Postgres
///
/// `docs/quality-bar.md` §5: a test that cannot assert only on its own data gets its own container
/// and says why. `startNewLineage()` truncates `task` and `task_patch` for **everybody**, so
/// against the shared container this class would delete what another class created — the same
/// reason `PortalArchiveImportIntegrationTest` has one.
@SpringBootTest(properties = {
        // No Keycloak: nothing here makes an HTTP call, so a decoder must exist and is never used.
        // `jwk-set-uri` rather than `issuer-uri`, which would fetch discovery eagerly at startup.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"})
@Import(ImportEpochIntegrationTest.OwnDatabase.class)
class ImportEpochIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class OwnDatabase {

        @Bean
        @ServiceConnection
        PostgreSQLContainer epochPostgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse(ComposeFile.imageOf("postgres")));
        }
    }

    @Autowired
    private TaskImport taskImport;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /// Twice, because once cannot tell a bump from a constant: `V5__sync_epoch.sql` seeds the row at
    /// 1, and an import that re-ran would be the second lineage, not the first.
    @Test
    void everyImportStartsALineageAndNamesIt() {
        var before = epoch();

        assertThat(taskImport.startNewLineage()).isEqualTo(before + 1);
        assertThat(epoch()).isEqualTo(before + 1);

        assertThat(taskImport.startNewLineage()).isEqualTo(before + 2);
        assertThat(epoch()).isEqualTo(before + 2);
    }

    /// The counter and the epoch move together or not at all. Sequence 1 is reissued the moment the
    /// truncate commits, so an epoch that lands in a later transaction leaves a window in which the
    /// server is handing out numbers it has already used under an epoch that still calls them
    /// unique — which is the defect itself, merely narrower.
    @Test
    void theRewoundCounterAndTheEpochBecomeVisibleTogether() {
        taskImport.startNewLineage();
        taskImport.importTask(UUID.randomUUID(), List.of(creationPatch()));

        var epochOfThatLineage = epoch();
        var firstSequence = jdbcClient.sql("SELECT MIN(sequence) FROM task_patch").query(Long.class).single();

        assertThat(firstSequence)
                .as("the counter was rewound, which is what makes the epoch load-bearing")
                .isEqualTo(1L);

        taskImport.startNewLineage();
        taskImport.importTask(UUID.randomUUID(), List.of(creationPatch()));

        assertThat(jdbcClient.sql("SELECT MIN(sequence) FROM task_patch").query(Long.class).single())
                .as("sequence 1 is issued again to a different patch")
                .isEqualTo(firstSequence);
        assertThat(epoch())
                .as("so the lineage it belongs to has a different name")
                .isEqualTo(epochOfThatLineage + 1);
    }

    /// **An import that fails halfway cannot leave a new lineage under the old epoch.** This is why
    /// the bump is the truncate's own statement rather than a step after the load: the load is
    /// thousands of transactions and any of them may be the last one.
    @Test
    void anImportThatFailsHalfwayLeavesNoUnnamedLineage() {
        taskImport.startNewLineage();
        var taskId = UUID.randomUUID();
        taskImport.importTask(taskId, List.of(creationPatch()));

        var before = epoch();
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            taskImport.startNewLineage();
            throw new IllegalStateException("the archive ran out halfway, as it may");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(epoch())
                .as("nothing was rewound, so nothing may claim a new lineage")
                .isEqualTo(before);
        assertThat(taskImport.taskCount())
                .as("and the history the epoch describes is still the one that is there")
                .isEqualTo(1L);
    }

    private long epoch() {
        return jdbcClient.sql("SELECT epoch FROM sync_epoch WHERE id = 1").query(Long.class).single();
    }

    /// The smallest history a task can be folded from — this test is about the counter, not the
    /// fold, which `PortalArchiveImportIntegrationTest` and the golden fixtures own.
    private static TaskImport.ImportedPatch creationPatch() {
        return new TaskImport.ImportedPatch(
                UUID.randomUUID(),
                Instant.parse("2020-01-01T10:00:00Z"),
                Map.of("name", "a task from a lineage that is about to end",
                        "context", "Personal",
                        "creationDateTime", "2020-01-01T10:00:00Z",
                        "startDate", "2020-01-01",
                        "importance", "IMPORTANT",
                        "status", "OPEN"));
    }
}
