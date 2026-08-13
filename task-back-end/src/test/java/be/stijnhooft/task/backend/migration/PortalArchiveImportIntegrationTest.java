package be.stijnhooft.task.backend.migration;

import be.stijnhooft.task.backend.ComposeFile;
import be.stijnhooft.task.backend.migration.diff.Cause;
import be.stijnhooft.task.backend.migration.diff.Difference;
import be.stijnhooft.task.backend.migration.portal.RecurringTasksReader;
import be.stijnhooft.task.backend.migration.portal.TodoReader;
import be.stijnhooft.task.backend.task.TaskImport;
import be.stijnhooft.task.backend.template.TaskTemplateImport;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/// **The dry run.** [#52](https://github.com/stainii/task/issues/52)'s *done when*, asserted against
/// the real frozen corpus rather than a fixture.
///
/// ### It skips unless the archive is restored
///
/// The corpus is 5 MB of the author's real personal data and is deliberately **not** in this
/// repository — [#31](https://github.com/stainii/task/issues/31) keeps the repo public and the rule
/// is that the code is not the secret, the data is. So CI cannot run this and does not pretend to:
/// with no restored archive reachable the whole class is skipped.
///
/// That is a real gap, stated rather than papered over: **the mapping rules are covered by the unit
/// tests beside this class, and only the arithmetic below is unproven in CI.** The numbers are
/// pinned here so that a change which breaks them fails on the author's machine before cutover,
/// which is the only machine where the archive exists.
///
/// Restore it first — see `~/portal-archive/2026-08-04/README.md`:
///
/// ```
/// docker run -d --name portal-pg -e POSTGRES_PASSWORD=portal -p 55432:5432 postgres:12
/// docker run -d --name portal-mongo -p 57017:27017 mongo:4.2.1
/// ```
@SpringBootTest(properties = {
        // No Keycloak: this test makes no HTTP call, so it needs a decoder to exist and never uses
        // one. `jwk-set-uri` rather than `issuer-uri` because the latter fetches discovery
        // eagerly at startup and this URL answers nothing.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"})
@Import(PortalArchiveImportIntegrationTest.OwnDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// `@EnabledIf` rather than an assumption inside the test: the condition is evaluated before the
// Spring context is built, so a CI run with no archive starts neither a context nor a container.
// An assumption would skip the assertions after paying for both.
@EnabledIf("archiveIsRestored")
class PortalArchiveImportIntegrationTest {

    /// **Its own Postgres, and this is not an optimisation.**
    ///
    /// The importer's first act is `TRUNCATE task_patch, task CASCADE`, and its last is 12,483 tasks
    /// and 39,450 patches. `docs/quality-bar.md` §5 says every other integration test shares one
    /// reused container and cleans up nothing, so running this against it does two unforgivable
    /// things at once: it deletes data another class created, and it leaves behind a hundred times
    /// more than any test expects to sweep.
    ///
    /// That is not hypothetical. Run against the shared container, this class made
    /// `DueTemplateCheckerIntegrationTest` hang for **31 minutes** and die on a closed connection —
    /// the quality bar's *test order changes what a test sees*, in its most expensive form.
    ///
    /// **Not reused**, unlike the shared one: a container that survives the run would keep 12,000
    /// imported tasks on the machine and hand them to the next run of this same class, which
    /// truncates first and so would pass either way while proving less.
    @TestConfiguration(proxyBeanMethods = false)
    static class OwnDatabase {

        @Bean
        @ServiceConnection
        PostgreSQLContainer importPostgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse(ComposeFile.imageOf("postgres")));
        }
    }

    private static final String MONGO_URI = "mongodb://localhost:57017";
    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:55432/";

    /// The four deployment names, spelled as `subscription.origin` spells them — which is *not* how
    /// the databases are named. `social-recurring-tasks` is the whole reason that distinction has a
    /// paragraph in ADR-0005.
    private static final List<String> DEPLOYMENTS =
            List.of("Housagotchi", "Setlist", "Health", "social-recurring-tasks");

    @Autowired
    private TaskImport taskImport;

    @Autowired
    private TaskTemplateImport taskTemplateImport;

    @Autowired
    private Clock clock;

    private ImportReport report;

    @BeforeAll
    void importTheArchive() {
        var deployments = DEPLOYMENTS.stream()
                .map(name -> new RecurringTasksReader(name, JdbcClient.create(new DriverManagerDataSource(
                        POSTGRES_URL + databaseOf(name), "postgres", "portal"))))
                .toList();

        try (var todo = new TodoReader(MONGO_URI, "todo")) {
            report = new PortalImporter(taskImport, taskTemplateImport, clock).importFrom(todo, deployments);
        }
        System.out.println(report.render());
    }

    /// The database name is the deployment name lowercased and prefixed — the case mismatch
    /// ADR-0005 predicted and [#35](https://github.com/stainii/task/issues/35) measured.
    private static String databaseOf(String deployment) {
        return "portal-" + deployment.toLowerCase(java.util.Locale.ROOT);
    }

    static boolean archiveIsRestored() {
        try (var client = MongoClients.create(MONGO_URI)) {
            return client.getDatabase("todo").getCollection("task").estimatedDocumentCount() > 0;
        } catch (RuntimeException notThere) {
            return false;
        }
    }

    /// The corpus, exactly as [#35](https://github.com/stainii/task/issues/35) counted it.
    @Test
    void theWholeArchiveIsRead() {
        assertThat(report.get("portal tasks read")).isEqualTo(11_855);
        assertThat(report.get("portal patches read")).isEqualTo(38_211);
    }

    /// **`Todo-<uuid>` does not abort the run** — ADR-0005 as first written would have refused
    /// healthy data, because `Todo` is a prefix with no recurring-tasks database and is entirely
    /// correct.
    @Test
    void allFourPrefixesReconcileAndTodoIsNotOneOfThem() {
        assertThat(report.get("deployments reconciled")).isEqualTo(4);
        assertThat(report.get("flowIds matching no known prefix")).isZero();
        assertThat(report.get("tasks with a Todo flow id (no template)")).isEqualTo(746);
    }

    /// Every task in, every task out. The 11 whose ids are not UUIDs are minted rather than dropped.
    @Test
    void everyTaskIsImported() {
        assertThat(report.get("tasks imported")).isEqualTo(11_855);
        assertThat(report.get("tasks written")).isEqualTo(11_855 + report.get("executions synthesised into tasks"));
    }

    /// **Patches in equals patches out**, minus the 17 that name a task existing nowhere, plus the
    /// two each synthesised task is folded from.
    @Test
    void thePatchArithmeticReconciles() {
        var dropped = report.get("patches naming no task (dropped)");
        var synthesised = report.get("executions synthesised into tasks");

        assertThat(dropped).isEqualTo(17);
        assertThat(report.get("patches written")).isEqualTo(38_211 - dropped + synthesised * 2);
    }

    /// The recurring corpus, and **ten tasks more than
    /// [#35](https://github.com/stainii/task/issues/35) counted**.
    ///
    /// #35 measured 8,086 by reading the `flowId` *field*. Ten of portal's earliest generated tasks
    /// carry no such field — their **id** is the flow id (`Health-1`, `Housagotchi-52`, …), from
    /// before the UUID scheme — so they were counted as hand-made. They are not: they name a real
    /// deployment and a real recurring task, and four of them still have a surviving template.
    ///
    /// So provenance is recovered for ten tasks a count taken from the field alone says have none,
    /// and `hand-made` drops by the same ten. The eleventh non-UUID id, a stray called `Healthy`,
    /// genuinely is hand-made: it names no deployment.
    @Test
    void provenanceIsRestoredForTheHalfThatStillHasATemplate() {
        assertThat(report.get("tasks generated by a deployment")).isEqualTo(8_096);
        assertThat(report.get("tasks whose template no longer exists (null link)")).isEqualTo(3_960);
        assertThat(report.get("tasks linked to their template")).isEqualTo(8_096 - 3_960);
    }

    @Test
    void theHandMadeTasksAreTheRest() {
        assertThat(report.get("hand-made tasks")).isEqualTo(3_013);
        assertThat(report.get("tasks defaulted to NOT_SO_IMPORTANT")).isEqualTo(17);
    }

    /// Every task in the archive accounts for itself in exactly one bucket.
    @Test
    void everyTaskLandsInExactlyOneBucket() {
        assertThat(report.get("tasks generated by a deployment")
                + report.get("tasks with a Todo flow id (no template)")
                + report.get("hand-made tasks")).isEqualTo(11_855);
    }

    /// Four tasks had their start date cleared in portal, and `startDate` is not nullable — the one
    /// place the importer invents a value rather than carrying one.
    @Test
    void theFourClearedStartDatesAreCountedNotHidden() {
        assertThat(report.get("cleared start dates defaulted to the creation date")).isEqualTo(4);
        assertThat(report.notes("cleared start date")).hasSize(4);
    }

    /// 44 `recurring_task` rows plus portal's 3 `taskTemplate` documents.
    @Test
    void everyTemplateIsImported() {
        assertThat(report.get("min/max templates written")).isEqualTo(44);
        assertThat(report.get("manual templates written")).isEqualTo(3);
        assertThat(report.get("templates written")).isEqualTo(47);
    }

    /// Only *unmatched* executions are synthesised: in portal one occurrence wrote both a todo task
    /// and an execution row, so synthesising unconditionally would put a phantom beside every real
    /// task.
    @Test
    void everyExecutionIsAccountedFor() {
        var read = report.get("executions read");
        assertThat(read).isEqualTo(5_620);
        assertThat(read).isEqualTo(report.get("executions matched by a migrated task")
                + report.get("executions matching several tasks (reported, not synthesised)")
                + report.get("executions synthesised into tasks"));
    }

    /// **Four differences the archive cannot explain**, out of 9,421 — [#53](https://github.com/stainii/task/issues/53)'s
    /// whole output, and the number a person actually reads.
    ///
    /// All four are portal being wrong rather than the fold. Three are stored values **not derivable
    /// from any patch in the task's own history** — ADR-0005's thesis arriving as a measurement
    /// rather than an argument — and the fourth is a due-date edit portal's `history` array dropped
    /// and the fold recovers.
    ///
    /// Pinned as an exact number on purpose. A fifth appearing is the report doing its job, and it
    /// has to be looked at rather than absorbed into a threshold.
    @Test
    void onlyFourDifferencesAreUnexplained() {
        assertThat(report.unexplained()).hasSize(4);
        assertThat(report.escalated()).isEmpty();
    }

    /// Every other difference is one of ADR-0005's named causes, in the proportions the mapping
    /// decisions predict: REC-011 overwrites a context for every generated task, `Contexts` folds
    /// four near-duplicate spellings, ADR-0018 defaults seventeen importances.
    @Test
    void everyOtherDifferenceIsANamedCause() {
        var byCause = report.differences().stream()
                .collect(java.util.stream.Collectors.groupingBy(Difference::cause, java.util.stream.Collectors.counting()));

        assertThat(byCause).containsOnly(
                entry(Cause.CONTEXT_OVERWRITTEN_BY_DEPLOYMENT, 8_096L),
                entry(Cause.CONTEXT_NORMALISED, 1_302L),
                entry(Cause.IMPORTANCE_DEFAULTED, 17L),
                entry(Cause.CLEARED_START_DATE_DEFAULTED, 2L),
                entry(Cause.UNEXPLAINED, 4L));
    }

    /// **D2's mechanism is present in the corpus and never corrupted anything.** Twelve tasks have a
    /// `history` array out of clock order and 81 carry a duplicated entry, so the preconditions are
    /// real — but in none of them did the mis-ordering change one of the eight compared fields.
    ///
    /// Asserted as zero rather than left unstated: if a future change to the fold's order made these
    /// fire, that would be the single most important thing about the run, and silence would hide it.
    @Test
    void noDifferenceIsCausedByPortalsMergeOrder() {
        assertThat(report.differences())
                .extracting(Difference::cause)
                .doesNotContain(Cause.OUT_OF_ORDER_ARRIVAL, Cause.DUPLICATED_HISTORY_ENTRY);
    }

    /// What [#39](https://github.com/stainii/task/issues/39) needs to know before it opens the
    /// dogfooding instance: **28 open tasks against 12,483**. Without the number in hand the app
    /// looks empty and reads as a bug.
    @Test
    void twentyEightTasksAreOpen() {
        assertThat(taskImport.openTaskCount()).isEqualTo(28);
    }

    /// ADR-0005's *re-runnable and idempotent, so dry runs are free* — the property every id in
    /// [be.stijnhooft.task.backend.migration.map.PortalIds] is minted deterministically to serve.
    @Test
    void asecondRunProducesTheSameDatabase() {
        var tasks = taskImport.taskCount();
        var patches = taskImport.patchCount();

        var deployments = DEPLOYMENTS.stream()
                .map(name -> new RecurringTasksReader(name, JdbcClient.create(new DriverManagerDataSource(
                        POSTGRES_URL + databaseOf(name), "postgres", "portal"))))
                .toList();
        try (var todo = new TodoReader(MONGO_URI, "todo")) {
            new PortalImporter(taskImport, taskTemplateImport, clock).importFrom(todo, deployments);
        }

        assertThat(taskImport.taskCount()).isEqualTo(tasks);
        assertThat(taskImport.patchCount()).isEqualTo(patches);
    }
}
