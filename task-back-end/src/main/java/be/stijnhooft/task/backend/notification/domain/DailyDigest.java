package be.stijnhooft.task.backend.notification.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/// **What one morning looks like in a notification shade** — the whole of ADR-0012's content
/// decision, as a value with no dependencies, so the rule is a plain unit test and not something
/// only a running application can show you.
///
/// ### It names what fits
///
/// *"Due today: Vacuum the house, Call Jan, +1 more"*. Naming is the one thing worth carrying over
/// from the mail this replaces: it let the author judge from the shade whether it mattered. A bare
/// count forces the app open to learn anything, which is a nag rather than information.
///
/// It stays **one** notification. Per-task notifications reintroduce exactly the volume that made
/// the mail wallpaper, and Android stacks them into a unit that is swiped away as a unit.
///
/// ### And it says nothing at all when there is nothing
///
/// An empty day sends nothing — not *"nothing due today"*. A notification that arrives every single
/// morning is the wallpaper failure ADR-0009 named, and this feature exists because the daily mail
/// became one.
public record DailyDigest(String title, String body, String url) {

    /// Three names, then a count. Enough that a normal day is fully named — the live system has 28
    /// open tasks in total — and short enough to survive Android's two-line collapsed notification,
    /// which is where this is read.
    private static final int NAMES_SHOWN = 3;

    /// ADR-0012: tapping the notification opens the overview, not a task. ADR-0006's always-visible
    /// band *is* the list the notification is about, so a deep link would be a second route to the
    /// same rows.
    private static final String OVERVIEW = "/";

    private static final String TITLE = "Due today";

    /// Empty for an empty day, which is the caller's cue to send nothing rather than to send
    /// something empty.
    public static Optional<DailyDigest> of(List<String> namesOfTasksDueToday) {
        if (namesOfTasksDueToday.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DailyDigest(TITLE, bodyOf(namesOfTasksDueToday), OVERVIEW));
    }

    /// **What actually goes on the wire**, and it is not this record's own three fields.
    ///
    /// `ngsw-worker.js` owns the `push` event — ADR-0012 chose Angular's `SwPush`, and that is the
    /// price of it — and its handler returns without showing anything unless the decrypted payload
    /// is `{"notification": {"title": …}}`. So a digest serialized as `{title, body, url}` is
    /// encrypted correctly, signed correctly, accepted by the push service with a `201`, logged as
    /// delivered, and displayed **nowhere**. Every failure in this feature is the same one — a
    /// channel that reports success it did not have — which is why the shape is asserted key by key
    /// in `DailyDigestTest` rather than left to a serializer.
    ///
    /// `onActionClick` is ngsw's vocabulary for *what a tap does*: `openWindow` on the overview,
    /// because ADR-0006's always-visible band **is** the list this notification is about.
    ///
    /// A `Map` rather than a nest of records because one of ngsw's keys is `default`, which is a
    /// Java keyword — three records and a `@JsonProperty` to describe a shape that is not ours to
    /// model.
    public Map<String, Object> asServiceWorkerPayload() {
        return Map.of("notification", Map.of(
                "title", title,
                "body", body,
                "data", Map.of("onActionClick", Map.of(
                        "default", Map.of("operation", "openWindow", "url", url)))));
    }

    private static String bodyOf(List<String> names) {
        if (names.size() <= NAMES_SHOWN) {
            return String.join(", ", names);
        }
        // The boundary is exactly here: four names read as three and "+1 more", never as
        // "+1 more" alone and never as four.
        return String.join(", ", names.subList(0, NAMES_SHOWN))
                + ", +" + (names.size() - NAMES_SHOWN) + " more";
    }
}
