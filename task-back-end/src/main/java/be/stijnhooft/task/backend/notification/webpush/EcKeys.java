package be.stijnhooft.task.backend.notification.webpush;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

/// P-256 keys in the shape the web speaks them: **uncompressed points and raw scalars**, base64url.
///
/// A browser hands out `p256dh` as 65 bytes of `0x04 || X || Y` ([X9.62] uncompressed form) and a
/// VAPID key pair is published the same way, because that is what `applicationServerKey` takes. The
/// JDK, meanwhile, speaks `ECPoint` and `BigInteger`. This class is the translation and nothing
/// else, so the encryption and the signing can both be read as the RFCs are written.
///
/// **Everything here is the JDK's own crypto.** No BouncyCastle, no provider registration - see
/// [WebPushEncryption] for why the library that would have brought them is not here.
final class EcKeys {

    static final String CURVE = "secp256r1";

    /// The uncompressed-point marker. Nothing here ever writes a compressed point, and a
    /// subscription that carried one would not be a browser's.
    private static final byte UNCOMPRESSED = 0x04;

    /// A P-256 coordinate is 32 bytes, always - left-padded, never trimmed. `BigInteger#toByteArray`
    /// is the trap: it emits a leading zero for a high bit and drops leading zero bytes, so a
    /// coordinate that happens to be small would produce a 64-byte point the push service rejects.
    private static final int COORDINATE_LENGTH = 32;

    private EcKeys() {
    }

    static KeyPair generateKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(CURVE));
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new WebPushException("Could not generate a P-256 key pair.", e);
        }
    }

    static ECPublicKey publicKeyOf(byte[] uncompressedPoint) {
        if (uncompressedPoint.length != 1 + 2 * COORDINATE_LENGTH || uncompressedPoint[0] != UNCOMPRESSED) {
            throw new WebPushException("A P-256 public key is 65 bytes starting with 0x04, not "
                    + uncompressedPoint.length + " bytes starting with 0x" + Integer.toHexString(uncompressedPoint[0] & 0xff) + ".");
        }
        var x = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1, 1 + COORDINATE_LENGTH));
        var y = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1 + COORDINATE_LENGTH, uncompressedPoint.length));
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), parameters()));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new WebPushException("Not a valid P-256 public key.", e);
        }
    }

    static ECPrivateKey privateKeyOf(byte[] scalar) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), parameters()));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new WebPushException("Not a valid P-256 private key.", e);
        }
    }

    static byte[] uncompressedPointOf(ECPublicKey key) {
        var point = new byte[1 + 2 * COORDINATE_LENGTH];
        point[0] = UNCOMPRESSED;
        writeCoordinate(key.getW().getAffineX(), point, 1);
        writeCoordinate(key.getW().getAffineY(), point, 1 + COORDINATE_LENGTH);
        return point;
    }

    /// The private half as the fixed-width scalar the `.env` carries, so a key can be round-tripped
    /// through configuration without a keystore.
    static byte[] scalarOf(ECPrivateKey key) {
        var scalar = new byte[COORDINATE_LENGTH];
        writeCoordinate(key.getS(), scalar, 0);
        return scalar;
    }

    static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /// Tolerant of padding, because a key pasted out of another tool may carry it and a `=` in a
    /// `.env` is the kind of thing that costs an evening.
    static byte[] decode(String base64Url) {
        try {
            return Base64.getUrlDecoder().decode(base64Url.trim().replace("=", ""));
        } catch (IllegalArgumentException e) {
            // Ours, not the JDK's: `WebPushException` is what `WebPushClient` reads as *this
            // subscription can never be sent to*, and a raw IllegalArgumentException would instead
            // be reported as a failed send and the dead row kept, retried every morning for ever.
            throw new WebPushException("Not valid base64url: " + base64Url, e);
        }
    }

    private static void writeCoordinate(BigInteger value, byte[] target, int offset) {
        var bytes = value.toByteArray();
        if (bytes.length > COORDINATE_LENGTH) {
            // A leading sign byte, which is the only way a P-256 coordinate exceeds 32 bytes.
            bytes = Arrays.copyOfRange(bytes, bytes.length - COORDINATE_LENGTH, bytes.length);
        }
        System.arraycopy(bytes, 0, target, offset + COORDINATE_LENGTH - bytes.length, bytes.length);
    }


    private static ECParameterSpec parameters() {
        try {
            var parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec(CURVE));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (NoSuchAlgorithmException | InvalidParameterSpecException e) {
            throw new WebPushException("This JVM cannot describe P-256, which every JDK can.", e);
        }
    }
}
