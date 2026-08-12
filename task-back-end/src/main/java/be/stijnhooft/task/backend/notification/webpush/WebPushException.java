package be.stijnhooft.task.backend.notification.webpush;

/// Something in the push path is wrong in a way no retry fixes: a malformed key, a JVM missing a
/// primitive, a subscription that is not one.
///
/// It is deliberately unchecked and deliberately not caught anywhere near the daily job's loop over
/// devices - `DailyPushService` catches per subscription for the same reason `DueTemplateChecker`
/// catches per template: one dead device is one dead device.
public class WebPushException extends RuntimeException {

    public WebPushException(String message) {
        super(message);
    }

    public WebPushException(String message, Throwable cause) {
        super(message, cause);
    }
}
