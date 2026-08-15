package be.stijnhooft.task.backend.config;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// The one endpoint that must answer a client carrying nothing at all.
///
/// Asserted rather than assumed, because the failure is circular and silent from the server's side:
/// a `401` here tells a browser to authenticate, and the only place it could learn how to is the
/// response it just did not get. `SpringSecurityConfig` exempts exactly one path, and a rename of
/// this controller's mapping would leave that exemption pointing at nothing while every test that
/// carries a token still passes.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientConfigIntegrationTest extends AbstractIntegrationTestCases {

    @LocalServerPort
    int port;

    /// Read back rather than hard-coded, so the assertion is *the URL and realm come from this
    /// property* rather than *they happen to be these strings today*.
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    String issuerUri;

    private RestTestClient restTestClient;

    @BeforeEach
    void setUp() {
        restTestClient = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void tellsAnUnauthenticatedClientWhereToLogIn() {
        restTestClient.get().uri("/api/config").exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .expectBody(ClientConfigDto.class)
                .value(config -> {
                    // Derived from the issuer this application validates against, never configured
                    // beside it: a browser must obtain its token from the realm the resource server
                    // checks, and two properties that ought to agree are two that can disagree.
                    assertThat(issuerUri).endsWith("/realms/test-realm");
                    assertThat(config.keycloak().url())
                            .isEqualTo(issuerUri.substring(0, issuerUri.lastIndexOf("/realms/")));
                    assertThat(config.keycloak().realm()).isEqualTo("test-realm");
                    assertThat(config.keycloak().clientId())
                            .isEqualTo("task");
                });
    }

    /// **ADR-0009's second fact, and the one that answers *did the deploy stop happening?*.**
    ///
    /// It rides here rather than on `/actuator/info` because a client that cannot authenticate can
    /// still reach this, and one public endpoint beats two.
    ///
    /// Asserted as a parseable instant rather than as a string, because the whole value of the fact
    /// is that a front end can compare it to its own build date. A `null` — which is what an absent
    /// `build-info.properties` would produce — would leave the banner permanently silent while
    /// every other test stayed green, so the controller refuses to start instead and this asserts
    /// the fact is really there.
    @Test
    void tellsAnyClientWhenTheBackEndWasBuilt() {
        restTestClient.get().uri("/api/config").exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .expectBody(ClientConfigDto.class)
                .value(config -> assertThat(Instant.parse(config.buildTime()))
                        .isBefore(Instant.now().plusSeconds(60)));
    }
}
