package be.stijnhooft.task.backend.notification.webpush;

import be.stijnhooft.task.backend.TestClock;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/// RFC 8292 has no worked example to compare against, so the token is checked the way a push service
/// checks it: **verify the signature with the public key, then read the claims.**
///
/// That is not a round-trip through our own code — `Signature#verify` is the JDK's, and it is the
/// same primitive Google's front door uses.
class VapidSignerTest {

    private static final LocalDate A_WINTER_DAY = LocalDate.of(2026, 1, 15);

    private final KeyPair keyPair = EcKeys.generateKeyPair();
    private final TestClock clock = TestClock.atNoonOn(A_WINTER_DAY);
    private final VapidKeys keys = VapidKeys.of(
            EcKeys.encode(EcKeys.uncompressedPointOf((ECPublicKey) keyPair.getPublic())),
            EcKeys.encode(EcKeys.scalarOf((ECPrivateKey) keyPair.getPrivate())),
            "mailto:someone@example.com");
    private final VapidSigner signer = new VapidSigner(keys, clock);

    @Test
    void signsATokenThePublicKeyVerifies() throws Exception {
        var header = signer.authorizationHeaderFor(URI.create("https://fcm.googleapis.com/fcm/send/abc123"));
        var token = tokenOf(header);

        var signingInput = token.substring(0, token.lastIndexOf('.'));
        var signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(keyPair.getPublic());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));

        assertThat(signature.verify(derOf(EcKeys.decode(token.substring(token.lastIndexOf('.') + 1)))))
                .isTrue();
    }

    /// The classic way to earn a `401`: `aud` must be the endpoint's **origin**, never the endpoint.
    /// A subscription identifier in a token that identifies the sender is rejected by every push
    /// service, and the message is not helpful.
    @Test
    void audienceIsTheOriginOfTheEndpointAndNotTheEndpoint() {
        var claims = claimsOf(signer.authorizationHeaderFor(
                URI.create("https://fcm.googleapis.com/fcm/send/abc123")));

        assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"");
        assertThat(claims).doesNotContain("abc123");
    }

    /// A local push service in a test speaks `http` on a port, and an origin that drops the port
    /// would authenticate against the wrong audience while looking right.
    @Test
    void audienceKeepsAnExplicitPort() {
        assertThat(claimsOf(signer.authorizationHeaderFor(URI.create("http://localhost:8123/push/xyz"))))
                .contains("\"aud\":\"http://localhost:8123\"");
    }

    /// `exp` is read from the `Clock` bean, not from the wall clock — the same rule as everywhere
    /// else (#44), and the reason a test can assert an absolute second rather than a range.
    @Test
    void expiryIsTwelveHoursOnTheApplicationsOwnClock() {
        assertThat(claimsOf(signer.authorizationHeaderFor(URI.create("https://push.example.net/p/1"))))
                .contains("\"exp\":" + clock.instant().plusSeconds(12 * 3600).getEpochSecond());
    }

    @Test
    void publishesThePublicKeyAlongsideTheToken() {
        assertThat(signer.authorizationHeaderFor(URI.create("https://push.example.net/p/1")))
                .startsWith("vapid t=")
                .endsWith(", k=" + keys.publicKeyAsString());
    }

    private static String tokenOf(String authorizationHeader) {
        return authorizationHeader.substring("vapid t=".length(), authorizationHeader.indexOf(", k="));
    }

    private static String claimsOf(String authorizationHeader) {
        var token = tokenOf(authorizationHeader);
        var parts = token.split("\\.");
        return new String(EcKeys.decode(parts[1]), StandardCharsets.UTF_8);
    }

    /// The inverse of `VapidSigner`'s own conversion: `R || S` back into the DER structure
    /// `Signature#verify` expects. Written out here rather than reused from production code, so a
    /// mistake in the conversion cannot cancel itself out.
    private static byte[] derOf(byte[] raw) {
        var r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32));
        var s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64));
        var rBytes = r.toByteArray();
        var sBytes = s.toByteArray();
        var der = new byte[6 + rBytes.length + sBytes.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + rBytes.length + sBytes.length);
        der[2] = 0x02;
        der[3] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, der, 4, rBytes.length);
        der[4 + rBytes.length] = 0x02;
        der[5 + rBytes.length] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, der, 6 + rBytes.length, sBytes.length);
        return der;
    }
}
