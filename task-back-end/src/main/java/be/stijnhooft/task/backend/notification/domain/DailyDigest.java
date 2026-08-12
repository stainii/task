package be.stijnhooft.task.backend.notification.domain;

import java.util.List;
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
