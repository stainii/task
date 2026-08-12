package be.stijnhooft.task.backend.notification.config;

import be.stijnhooft.task.backend.notification.webpush.VapidKeys;
import be.stijnhooft.task.backend.notification.webpush.WebPushClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/// The VAPID key pair, read once at startup.
///
/// **There is no default and no empty fallback.** A missing or malformed key pair stops the
/// application rather than producing a push client that fails every morning into a log — the
/// distinction ADR-0009 drew between *the app is dead*, which is visible, and a silent no-op, which
/// is `echo "Backup completed"`. The committed values are deliberately worthless dev ones per
/// [#31](https://github.com/stainii/task/issues/31); production supplies its own from the gitignored
/// `.env`, and **losing that key invalidates every existing subscription**
/// ([#26](https://github.com/stainii/task/issues/26)'s *restore config, not just data*).
@Configuration
public class PushConfig {

    @Bean
    public VapidKeys vapidKeys(@Value("${task.push.vapid.public-key}") String publicKey,
                               @Value("${task.push.vapid.private-key}") String privateKey,
                               @Value("${task.push.vapid.subject}") String subject) {
        return VapidKeys.of(publicKey, privateKey, subject);
    }

    @Bean
    public WebPushClient webPushClient(VapidKeys vapidKeys, Clock clock) {
        return new WebPushClient(vapidKeys, clock);
    }
}
