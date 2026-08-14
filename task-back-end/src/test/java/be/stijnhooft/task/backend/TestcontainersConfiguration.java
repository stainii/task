package be.stijnhooft.task.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /// Whether this JVM has already emptied the shared database. See `emptyOnce`.
    private static final AtomicBoolean EMPTIED = new AtomicBoolean(false);

    /// `withReuse(true)` does nothing until you opt in per machine - see "Faster local test
    /// runs" in README.md. It is kept rather than removed because opting in is the difference
    /// between starting Postgres and Keycloak on every run and starting them once a week, and
    /// #21 established it is inert in CI either way (a fresh runner has nothing to reuse).
    /// Reuse is also why the Keycloak container in AbstractIntegrationTestCases is never
    /// stopped: stopping it would defeat the flag on the very next run.
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        var container = new PostgreSQLContainer(DockerImageName.parse(ComposeFile.imageOf("postgres")))
                .withReuse(true);
        container.start();
        emptyOnce(container);
        return container;
    }

    /// **Every run starts on an empty database, exactly as CI does.**
    ///
    /// `docs/quality-bar.md` §5 says nothing is cleaned up *between tests*, and that stays true -
    /// this runs once per JVM, before the first test touches Postgres, so no test ever sees another
    /// test's data disappear. What it deletes is the *previous run's* leftovers, which no test in
    /// this run is entitled to see anyway.
    ///
    /// Without it a reused container grows without bound, and one endpoint turns that into a
    /// failure: `GET /api/tasks` returns **every** open task with its full patch history, and the
    /// tests that read a snapshot do so through `WebTestClient`'s default **256 KB** buffer.
    ///
    /// **Measured rather than predicted.** Each full run leaves exactly **+34 open tasks** behind,
    /// and the count is dead linear: 63, 97, 131, 165, 199, 233, 267, 301, 334. On **run 8** the
    /// suite failed with four
    /// `DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144`, in
    /// `TaskModuleIntegrationTest.thePatchThatIsSentTwiceIsAcceptedTwiceAndStoredOnce` and three
    /// tests of `TaskPatchStreamResumeIntegrationTest` - **no importer involved anywhere**, just
    /// the suite's own leftovers. 301 open tasks passed and 334 did not, so the wall is around 320.
    ///
    /// The importer is what made this look like an importer problem: a stale run of it had left
    /// 12,850 tasks in the shared container, reaching the same wall in one step instead of eight.
    /// Its own private container works exactly as [#52](https://github.com/stainii/task/issues/52)
    /// intended - a full run leaves the shared database on 89 rows, not 12,850 - so those were
    /// leftovers from before that container existed, and the accumulation below is the real defect
    /// they were hiding.
    ///
    /// **CI can never see this**, because CI is always run #1 on a fresh container - which is the
    /// whole shape `docs/quality-bar.md` is written around, and the reason the guard belongs here
    /// rather than in a note asking people to remember. What it buys is precisely *local runs and
    /// CI runs mean the same thing*.
    ///
    /// **The buffer is deliberately left at its default.** Raising it would postpone this rather
    /// than fix it - accumulation is unbounded and beats any constant - and it would also hide this
    /// guard failing. At 256 KB, a return of the growth fails loudly and early instead of years
    /// later.
    ///
    /// `DROP SCHEMA` rather than `TRUNCATE`: it works whether or not the tables exist yet, so a
    /// fresh container and a week-old one take the same path, and it takes `flyway_schema_history`
    /// with it so Flyway rebuilds from V1. That is what makes "reused" and "fresh" indistinguishable
    /// rather than merely similar.
    ///
    /// The one case this does not cover is **two `./mvnw verify` runs at once on one machine**,
    /// where the second would empty the first's database mid-run. Surefire forks once and runs
    /// sequentially, so it cannot happen inside a run; across concurrent runs the shared container
    /// was already unsafe before this method existed, for the same reason.
    private static void emptyOnce(PostgreSQLContainer container) {
        if (!EMPTIED.compareAndSet(false, true)) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not empty the shared test database, so this run would have started on the "
                            + "previous run's data. See TestcontainersConfiguration#emptyOnce.", e);
        }
    }

}
