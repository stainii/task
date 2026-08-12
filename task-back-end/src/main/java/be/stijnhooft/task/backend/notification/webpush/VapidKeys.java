package be.stijnhooft.task.backend.notification.webpush;

import java.security.interfaces.ECPrivateKey;

/// The application server's identity: one stable P-256 key pair and a contact address.
///
/// `subject` is a `mailto:` or `https:` URL the push service can complain to if our traffic
/// misbehaves. It is not authentication and it is not optional - several push services reject a
/// token without one.
///
/// The public half is kept as the string the browser is handed verbatim (`applicationServerKey`),
/// because that is the only form it is ever used in; the private half is decoded once.
public record VapidKeys(String publicKeyAsString, ECPrivateKey privateKey, String subject) {

    /// **Decoded at startup, not at first send.** A mistyped key in the `.env` is then a failure to
    /// start - which ADR-0009 already treats as visible - rather than a morning with no notification
    /// and one line in a log nobody reads. That is the same choice ADR-0016 made for the due check's
    /// schedule and the opposite of `echo "Backup completed"`.
    public static VapidKeys of(String publicKey, String privateKey, String subject) {
        if (publicKey.isBlank() || privateKey.isBlank()) {
            throw new WebPushException("No VAPID key pair is configured. Set task.push.vapid.public-key "
                    + "and task.push.vapid.private-key (production supplies them from the .env).");
        }
        if (subject.isBlank()) {
            throw new WebPushException("task.push.vapid.subject must be a mailto: or https: URL.");
        }
        var decodedPrivateKey = EcKeys.privateKeyOf(EcKeys.decode(privateKey));
        EcKeys.publicKeyOf(EcKeys.decode(publicKey));
        return new VapidKeys(publicKey.trim(), decodedPrivateKey, subject);
    }
}
