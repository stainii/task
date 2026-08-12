package be.stijnhooft.task.backend.notification.webpush;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/// **Who is sending this**, per [RFC 8292](https://www.rfc-editor.org/rfc/rfc8292): a one-hop JWT
/// signed with the application server's stable key pair, so the push service can attribute traffic
/// to us and rate-limit it rather than refuse it.
///
/// This is a *different* key pair from the ephemeral one in [WebPushEncryption]. That one is minted
/// per message and exists to encrypt; this one is minted once, lives in the gitignored `.env`
/// ([#31](https://github.com/stainii/task/issues/31)), is published to the browser as
/// `applicationServerKey`, and **its loss invalidates every existing subscription** - the first
/// concrete artifact behind [#26](https://github.com/stainii/task/issues/26)'s *restore config, not
/// just data*. The re-subscribe-on-open rule heals it silently, which is the only reason losing it
/// is survivable.
///
/// ### ES256 is not what `Signature` hands you
///
/// The JDK signs `SHA256withECDSA` into a **DER** structure; JWS requires the raw `R || S`, 32 bytes
/// each, fixed width. A DER integer carries a sign byte and drops leading zeros, so the naive
/// conversion produces a signature that verifies locally and is rejected by the push service
/// roughly one time in 256. It is converted here, deliberately, once.
final class VapidSigner {

    /// RFC 8292 §2 caps the token's life at 24 hours. Twelve, because the token is minted per send
    /// and a shorter life costs nothing - there is no cache to warm.
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);

    private static final String HEADER = """
            {"typ":"JWT","alg":"ES256"}""";

    private static final int COORDINATE_LENGTH = 32;

    private final VapidKeys keys;
    private final Clock clock;

    VapidSigner(VapidKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    /// The `Authorization` header for one send.
    ///
    /// `aud` is the **origin** of the endpoint and nothing more - scheme, host and port. Sending the
    /// full endpoint URL is the classic mistake here: it identifies the subscription in a token that
    /// is meant to identify the sender, and every push service answers `401`.
    String authorizationHeaderFor(URI endpoint) {
        var claims = """
                {"aud":"%s","exp":%d,"sub":"%s"}"""
                .formatted(originOf(endpoint), clock.instant().plus(TOKEN_LIFETIME).getEpochSecond(), keys.subject());

        var signingInput = EcKeys.encode(HEADER.getBytes(StandardCharsets.UTF_8))
                + "." + EcKeys.encode(claims.getBytes(StandardCharsets.UTF_8));
        var token = signingInput + "." + EcKeys.encode(sign(signingInput));

        return "vapid t=" + token + ", k=" + keys.publicKeyAsString();
    }

    private byte[] sign(String signingInput) {
        try {
            var signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keys.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return toFixedWidthRawSignature(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new WebPushException("Could not sign the VAPID token.", e);
        }
    }

    /// `SEQUENCE { INTEGER r, INTEGER s }` to `R || S`, both left-padded to 32 bytes.
    private static byte[] toFixedWidthRawSignature(byte[] der) {
        if (der.length < 8 || der[0] != 0x30) {
            throw new WebPushException("The JVM did not produce a DER ECDSA signature.");
        }
        // Lengths here are always one byte: a P-256 signature is far below the 128-byte threshold
        // where DER switches to long form.
        var rLength = der[3];
        var r = new BigInteger(Arrays.copyOfRange(der, 4, 4 + rLength));
        var sOffset = 4 + rLength + 2;
        var s = new BigInteger(Arrays.copyOfRange(der, sOffset, sOffset + der[sOffset - 1]));

        var raw = new byte[2 * COORDINATE_LENGTH];
        writeUnsigned(r, raw, 0);
        writeUnsigned(s, raw, COORDINATE_LENGTH);
        return raw;
    }

    private static void writeUnsigned(BigInteger value, byte[] target, int offset) {
        var bytes = value.toByteArray();
        var start = Math.max(0, bytes.length - COORDINATE_LENGTH);
        var length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + COORDINATE_LENGTH - length, length);
    }

    private static String originOf(URI endpoint) {
        var origin = endpoint.getScheme() + "://" + endpoint.getHost();
        return endpoint.getPort() == -1 ? origin : origin + ":" + endpoint.getPort();
    }
}
