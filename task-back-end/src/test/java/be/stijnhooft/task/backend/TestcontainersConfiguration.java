package be.stijnhooft.task.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {


    /// `withReuse(true)` does nothing until you opt in per machine - see "Faster local test
    /// runs" in README.md. It is kept rather than removed because opting in is the difference
    /// between starting Postgres and Keycloak on every run and starting them once a week, and
    /// #21 established it is inert in CI either way (a fresh runner has nothing to reuse).
    /// Reuse is also why the Keycloak container in AbstractIntegrationTestCases is never
    /// stopped: stopping it would defeat the flag on the very next run.
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(ComposeFile.imageOf("postgres")))
                .withReuse(true);
    }

}
