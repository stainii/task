package be.stijnhooft.task.backend.notification.webpush;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/// **The IETF supplies the fixture.**
///
/// `WebPushEncryption` is written out rather than pulled in (see that class for the measurement),
/// and this is what makes that defensible: [RFC 8291 §5](https://www.rfc-editor.org/rfc/rfc8291#section-5)
/// publishes a complete worked example - receiver key, auth secret, sender key, salt, plaintext and
/// the exact body on the wire - so the implementation is not asserted to be correct, it is compared
/// against the specification's own answer, byte for byte.
///
/// It is the same mechanism as `/fold-fixtures/`: a rule that exists in two places (here, and in
/// every push service on the internet) is pinned by a fixture rather than by a reading.
class WebPushEncryptionTest {

    // RFC 8291 section 5, verbatim. The line breaks in the RFC are presentation only.
    private static final String RECEIVER_PUBLIC_KEY =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String SENDER_PUBLIC_KEY =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String SENDER_PRIVATE_KEY = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String EXPECTED_BODY =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
                    + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
                    + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private final WebPushEncryption encryption = new WebPushEncryption();

    @Test
    void reproducesTheWorkedExampleFromRfc8291() {
        var body = encryption.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                EcKeys.publicKeyOf(EcKeys.decode(RECEIVER_PUBLIC_KEY)),
                EcKeys.decode(AUTH_SECRET),
                senderKeyPairFromTheRfc(),
                EcKeys.decode(SALT));

        assertThat(EcKeys.encode(body)).isEqualTo(EXPECTED_BODY);
    }

    /// The header is what the receiver reads before it can derive anything, and it is fixed-width:
    /// 16 bytes of salt, a four-byte record size, one length byte, then 65 bytes of key. Asserted
    /// separately from the ciphertext so a header mistake is not reported as an encryption mistake.
    @Test
    void writesTheRecordHeaderRfc8188Describes() {
        var body = encryption.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                EcKeys.publicKeyOf(EcKeys.decode(RECEIVER_PUBLIC_KEY)),
                EcKeys.decode(AUTH_SECRET));

        assertThat(Arrays.copyOfRange(body, 16, 20)).containsExactly(0x00, 0x00, 0x10, 0x00);
        assertThat(body[20]).isEqualTo((byte) 65);
        assertThat(body[21]).isEqualTo((byte) 0x04);
    }

    /// Every message must be its own encryption context: a repeated salt with a repeated key pair
    /// is a repeated AES-GCM nonce, which is the one way to break this construction outright. The
    /// production entry point takes neither from its caller, and this is what says so.
    @Test
    void mintsAFreshSaltAndKeyPairPerMessage() {
        var receiver = EcKeys.publicKeyOf(EcKeys.decode(RECEIVER_PUBLIC_KEY));
        var authSecret = EcKeys.decode(AUTH_SECRET);
        var plaintext = PLAINTEXT.getBytes(StandardCharsets.UTF_8);

        var first = encryption.encrypt(plaintext, receiver, authSecret);
        var second = encryption.encrypt(plaintext, receiver, authSecret);

        assertThat(Arrays.copyOf(first, 16)).isNotEqualTo(Arrays.copyOf(second, 16));
        assertThat(Arrays.copyOfRange(first, 21, 86)).isNotEqualTo(Arrays.copyOfRange(second, 21, 86));
    }

    private static KeyPair senderKeyPairFromTheRfc() {
        return new KeyPair(
                EcKeys.publicKeyOf(EcKeys.decode(SENDER_PUBLIC_KEY)),
                EcKeys.privateKeyOf(EcKeys.decode(SENDER_PRIVATE_KEY)));
    }
}
