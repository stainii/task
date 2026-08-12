package be.stijnhooft.task.backend.notification;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.notification.domain.PushSubscription;
import be.stijnhooft.task.backend.notification.dto.PushSubscriptionDto;
import be.stijnhooft.task.backend.notification.dto.PushSubscriptionRemovalDto;
import be.stijnhooft.task.backend.notification.repository.PushSubscriptionRepository;
import be.stijnhooft.task.backend.task.DueTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `notification` bootstrapping alone, which is the point of a module test: it needs `task` only
/// through `DueTasks`, and the mock is what proves that
/// ([ADR-0003](../../../../../../../../docs/adr/0003-two-modules-with-package-visibility-as-the-boundary.md)'s
/// dependency runs one way, `notification → task`).
///
/// Per `docs/quality-bar.md` §5 every assertion here is about a subscription this class created,
/// identified by an endpoint carrying its own UUID — the suite shares one Postgres and cleans
/// nothing.
@ApplicationModuleTest(extraIncludes = "config", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationModuleIntegrationTest extends AbstractIntegrationTestCases {

    @LocalServerPort
    int port;

    @Autowired
    private PushSubscriptionRepository repository;

    @MockitoBean
    private DueTasks dueTasks;

    private RestTestClient restTestClient;

    @BeforeEach
    void setUp() {
        this.restTestClient = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void registersADeviceAndKeepsIt() {
        var subscription = aSubscription();

        register(subscription).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(storedFor(subscription.endpoint()))
                .get()
                .satisfies(stored -> {
                    assertThat(stored.p256dh()).isEqualTo(subscription.keys().p256dh());
                    assertThat(stored.auth()).isEqualTo(subscription.keys().auth());
                    assertThat(stored.registeredOn()).isNotNull();
                });
    }

    /// **The client re-registers on every app open** (ADR-0012's repair mechanism), so this call is
    /// made far more often than a device is added. A plain insert would give one phone a row a day
    /// and one notification per row, and the unique constraint would turn the second launch of the
    /// day into a `500`.
    @Test
    void registeringTheSameDeviceTwiceChangesNothing() {
        var subscription = aSubscription();

        register(subscription).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);
        var first = storedFor(subscription.endpoint()).orElseThrow();

        register(subscription).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(storedFor(subscription.endpoint()))
                .get()
                .extracting(PushSubscription::id)
                .isEqualTo(first.id());
    }

    /// Chrome rotates a subscription's keys without changing its endpoint. Ignoring the second
    /// registration would leave the device listed and unreachable — registered, pushed to every
    /// morning, and decryptable nowhere.
    @Test
    void aRotatedKeyUpdatesTheDeviceRatherThanAddingOne() {
        var subscription = aSubscription();
        register(subscription).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);
        var before = storedFor(subscription.endpoint()).orElseThrow();

        var rotated = new PushSubscriptionDto(subscription.endpoint(),
                new PushSubscriptionDto.Keys(subscription.keys().p256dh(), "a-rotated-auth-secret"));
        register(rotated).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(storedFor(subscription.endpoint()))
                .get()
                .satisfies(stored -> {
                    assertThat(stored.id()).isEqualTo(before.id());
                    assertThat(stored.auth()).isEqualTo("a-rotated-auth-secret");
                });
    }

    @Test
    void forgetsADeviceWhenTheToggleGoesOff() {
        var subscription = aSubscription();
        register(subscription).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        forget(subscription.endpoint()).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(storedFor(subscription.endpoint())).isEmpty();
    }

    /// A second tap on an off toggle is not a failure — it asks for the state the system is already
    /// in. A `404` here would surface in the UI as *something went wrong* while nothing had.
    @Test
    void forgettingADeviceTwiceIsNotAnError() {
        var subscription = aSubscription();
        register(subscription).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        forget(subscription.endpoint()).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);
        forget(subscription.endpoint()).expectStatus().isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesASubscriptionWithoutKeys() {
        restTestClient.post()
                .uri("/api/push-subscriptions")
                .header("Authorization", getAuthorizationHeaderForUser())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"endpoint\":\"https://push.example.net/p/" + UUID.randomUUID() + "\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    /// The browser cannot subscribe without it, and ADR-0010 says every `/api` request carries the
    /// realm role — a personal app has no reason to publish even a public key to anyone who asks.
    @Test
    void handsOutTheApplicationServerKeyToAnAuthenticatedClient() {
        restTestClient.get()
                .uri("/api/push-subscriptions/application-server-key")
                .header("Authorization", getAuthorizationHeaderForUser())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(key -> assertThat(key).startsWith("B"));
    }

    @Test
    void refusesAnUnauthenticatedRegistration() {
        restTestClient.post()
                .uri("/api/push-subscriptions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(aSubscription())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static PushSubscriptionDto aSubscription() {
        return new PushSubscriptionDto(
                "https://push.example.net/p/" + UUID.randomUUID(),
                new PushSubscriptionDto.Keys(
                        "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
                        "BTBZMqHH6r4Tts7J_aSIgg"));
    }

    private RestTestClient.ResponseSpec register(PushSubscriptionDto subscription) {
        return restTestClient.post()
                .uri("/api/push-subscriptions")
                .header("Authorization", getAuthorizationHeaderForUser())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(subscription)
                .exchange();
    }

    private RestTestClient.ResponseSpec forget(String endpoint) {
        return restTestClient.post()
                .uri("/api/push-subscriptions/removal")
                .header("Authorization", getAuthorizationHeaderForUser())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new PushSubscriptionRemovalDto(endpoint))
                .exchange();
    }

    private java.util.Optional<PushSubscription> storedFor(String endpoint) {
        return repository.findByEndpoint(endpoint);
    }
}
