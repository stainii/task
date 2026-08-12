package be.stijnhooft.task.backend.notification.webpush;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/// **The one thing that leaves the box.**
///
/// One `POST` per device per day to whatever push service that device's browser named, carrying a
/// body only that device can read. ADR-0012 accepted this knowingly: it is the only outbound
/// dependency in the application, and ADR-0009's *no infrastructure at all* survives it because
/// there is nothing here we run.
///
/// The transport is `java.net.http.HttpClient` - in the JDK, HTTP/2 by default, which is what the
/// push services speak. See [WebPushEncryption] for why there is no library underneath it.
@Slf4j
public class WebPushClient {

    /// How long the push service should hold a message for a device that is offline. Four hours:
    /// the message says *what is due today* and a phone switched on in the evening is better served
    /// by the overview than by a morning notification arriving at dinner. It is not zero, because a
    /// phone in a pocket at 07:30 is the normal case, not an edge one.
    private static final Duration TIME_TO_LIVE = Duration.ofHours(4);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final WebPushEncryption encryption = new WebPushEncryption();
    private final VapidSigner vapidSigner;

    public WebPushClient(VapidKeys vapidKeys, Clock clock) {
        this.vapidSigner = new VapidSigner(vapidKeys, clock);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /// Encrypt for this device, sign for us, post, and report which of the three things happened.
    ///
    /// **Nothing is thrown for a dead subscription or a failed send.** The caller loops over every
    /// device, and an exception per device is how one dead phone stops the other from being told
    /// what is due - the same failure `DueTemplateChecker` catches per template.
    public PushOutcome send(PushTarget target, byte[] payload) {
        URI endpoint;
        try {
            endpoint = URI.create(target.endpoint());
        } catch (IllegalArgumentException e) {
            log.warn("A push subscription carries an endpoint that is not a URL; treating it as gone.", e);
            return PushOutcome.GONE;
        }

        try {
            var body = encryption.encrypt(
                    payload,
                    EcKeys.publicKeyOf(EcKeys.decode(target.p256dh())),
                    EcKeys.decode(target.auth()));

            var request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", vapidSigner.authorizationHeaderFor(endpoint))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", String.valueOf(TIME_TO_LIVE.toSeconds()))
                    // RFC 8030: this message is worth waking a dozing device for, and no more.
                    .header("Urgency", "normal")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            return outcomeOf(endpoint, httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            log.warn("Could not reach the push service at {}.", endpoint.getHost(), e);
            return PushOutcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushOutcome.FAILED;
        } catch (WebPushException e) {
            // A subscription whose keys will not decode can never be sent to again, so it is dead
            // in exactly the sense a 410 is dead. Keeping it would mean failing on it every morning.
            log.warn("A push subscription for {} could not be encrypted for; treating it as gone.", endpoint.getHost(), e);
            return PushOutcome.GONE;
        }
    }

    private static PushOutcome outcomeOf(URI endpoint, HttpResponse<String> response) {
        var status = response.statusCode();
        if (status == 404 || status == 410) {
            return PushOutcome.GONE;
        }
        if (status >= 200 && status < 300) {
            return PushOutcome.DELIVERED;
        }
        // The body is logged because the push services put the actual complaint there - a bad
        // VAPID claim comes back as 401 with a sentence, and without it this is unsolvable.
        log.warn("The push service at {} answered {}: {}", endpoint.getHost(), status, response.body());
        return PushOutcome.FAILED;
    }
}
