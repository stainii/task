package be.stijnhooft.task.backend.notification.webpush;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/// **`aes128gcm` message encryption, from the RFC and nothing else**
/// ([RFC 8291](https://www.rfc-editor.org/rfc/rfc8291) over
/// [RFC 8188](https://www.rfc-editor.org/rfc/rfc8188)).
///
/// ### Why this is written out rather than pulled in
///
/// [ADR-0012](../../../../../../../../docs/adr/0012-one-push-at-0730-derived-not-stored.md) named
/// `nl.martijndwars:web-push` 5.1.2. It was measured before it was taken, and it costs **26 jars**
/// to send one HTTPS POST a day: Netty 4.1.60 and async-http-client 2.12.4 (2021), Apache
/// httpasyncclient, jcommander, jose4j, an explicit BouncyCastle provider - and `slf4j-api` **1.7.30**
/// on the runtime classpath of an application that runs slf4j 2. It is also the library's last
/// release. Against this map's standing *prefer fewer moving parts*, and against a Renovate setup
/// ([#25](https://github.com/stainii/task/issues/25)) that would offer bumps a pinned 2022 library
/// cannot take, that is a bad trade for the ~150 lines below.
///
/// **Nothing here is invented.** Every step is the RFC's, in the RFC's order, and the whole thing is
/// pinned by the worked example in [RFC 8291 §5](https://www.rfc-editor.org/rfc/rfc8291#section-5):
/// given that section's receiver key, auth secret, sender key and salt, this class must produce that
/// section's body **byte for byte**. That fixture is the reason writing it out is defensible - it is
/// the same argument as `/fold-fixtures/`, with the IETF supplying the fixture.
///
/// ### The shape of a message
///
/// ```text
/// salt (16) | record size (4) | key length (1) = 65 | sender public key (65) | AES-GCM ciphertext
/// ```
///
/// The receiver needs the sender's public key and the salt to derive the same secret, so both travel
/// in the clear in the header - the security comes from the ECDH shared secret and the subscription's
/// `auth` secret, not from hiding them.
final class WebPushEncryption {

    /// RFC 8188's record size. One record, and the payload is a task list, so this is never the
    /// binding limit - the push services' own 4 KB is.
    private static final int RECORD_SIZE = 4096;

    /// RFC 8291 §3.3: the salt is 16 bytes, fresh per message. Reusing one with the same key pair
    /// would reuse an AES-GCM nonce, which is the one way to break this construction outright.
    private static final int SALT_LENGTH = 16;

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    /// RFC 8188 §2: the last (here: only) record ends with `0x02`. `0x01` would say *another record
    /// follows*, and the receiver would wait for one that never comes.
    private static final byte LAST_RECORD_DELIMITER = 0x02;

    private static final int CONTENT_ENCRYPTION_KEY_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int AUTHENTICATION_TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    /// The production path: a **fresh** ephemeral key pair and a fresh salt per message, which is
    /// what makes every message its own encryption context.
    byte[] encrypt(byte[] plaintext, ECPublicKey receiverPublicKey, byte[] authSecret) {
        var salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return encrypt(plaintext, receiverPublicKey, authSecret, EcKeys.generateKeyPair(), salt);
    }

    /// The same thing with the two random inputs supplied, which is the only way the RFC's worked
    /// example can be reproduced - and therefore the only way this file is provably right.
    byte[] encrypt(byte[] plaintext, ECPublicKey receiverPublicKey, byte[] authSecret, KeyPair senderKeyPair, byte[] salt) {
        var senderPublicKey = EcKeys.uncompressedPointOf((ECPublicKey) senderKeyPair.getPublic());
        var receiverPublicPoint = EcKeys.uncompressedPointOf(receiverPublicKey);

        try {
            var sharedSecret = agree(senderKeyPair, receiverPublicKey);

            // RFC 8291 §3.4. The receiver's key comes first in key_info, and getting that order
            // wrong produces a message that encrypts cleanly and decrypts nowhere.
            var keyInfo = concat(KEY_INFO_PREFIX, receiverPublicPoint, senderPublicKey);
            var inputKeyingMaterial = hkdf(authSecret, sharedSecret, keyInfo, 32);

            var contentEncryptionKey = hkdf(salt, inputKeyingMaterial, CEK_INFO, CONTENT_ENCRYPTION_KEY_LENGTH);
            var nonce = hkdf(salt, inputKeyingMaterial, NONCE_INFO, NONCE_LENGTH);

            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(contentEncryptionKey, "AES"),
                    new GCMParameterSpec(AUTHENTICATION_TAG_BITS, nonce));
            var ciphertext = cipher.doFinal(concat(plaintext, new byte[]{LAST_RECORD_DELIMITER}));

            return header(salt, senderPublicKey, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new WebPushException("Could not encrypt a push message.", e);
        }
    }

    private static byte[] agree(KeyPair senderKeyPair, ECPublicKey receiverPublicKey) throws GeneralSecurityException {
        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(senderKeyPair.getPrivate());
        agreement.doPhase(receiverPublicKey, true);
        return agreement.generateSecret();
    }

    /// HKDF (RFC 5869) with SHA-256, expanded to at most one block - which is all any step here
    /// asks for, so the counter is a constant `0x01` rather than a loop nothing would exercise.
    private static byte[] hkdf(byte[] salt, byte[] inputKeyingMaterial, byte[] info, int length) throws GeneralSecurityException {
        var pseudoRandomKey = hmac(salt, inputKeyingMaterial);
        return Arrays.copyOf(hmac(pseudoRandomKey, concat(info, new byte[]{0x01})), length);
    }

    private static byte[] hmac(byte[] key, byte[] message) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static byte[] header(byte[] salt, byte[] senderPublicKey, byte[] ciphertext) {
        return ByteBuffer.allocate(salt.length + 4 + 1 + senderPublicKey.length + ciphertext.length)
                .put(salt)
                .putInt(RECORD_SIZE)
                .put((byte) senderPublicKey.length)
                .put(senderPublicKey)
                .put(ciphertext)
                .array();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
