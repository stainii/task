package be.stijnhooft.task.backend.notification.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The content rule, at its boundaries (`docs/quality-bar.md` §5). Everything ADR-0012 decided about
/// what a morning *reads like* is here, and none of it needs a database, a clock or a network.
class DailyDigestTest {

    @Test
    void anEmptyDaySaysNothingAtAll() {
        assertThat(DailyDigest.of(List.of())).isEmpty();
    }

    @Test
    void oneTaskIsNamed() {
        assertThat(DailyDigest.of(List.of("Vacuum the house")))
                .get()
                .satisfies(digest -> {
                    assertThat(digest.title()).isEqualTo("Due today");
                    assertThat(digest.body()).isEqualTo("Vacuum the house");
                    assertThat(digest.url()).isEqualTo("/");
                });
    }

    /// Three is the boundary in both directions: three names read as three names, and the fourth is
    /// what turns the tail into a count rather than a fourth name.
    @Test
    void threeTasksAreAllNamed() {
        assertThat(DailyDigest.of(List.of("Vacuum the house", "Call Jan", "Water the plants")))
                .get()
                .extracting(DailyDigest::body)
                .isEqualTo("Vacuum the house, Call Jan, Water the plants");
    }

    @Test
    void theFourthTaskBecomesACount() {
        assertThat(DailyDigest.of(List.of("Vacuum the house", "Call Jan", "Water the plants", "Bin out")))
                .get()
                .extracting(DailyDigest::body)
                .isEqualTo("Vacuum the house, Call Jan, Water the plants, +1 more");
    }

    @Test
    void aBusyDayStillNamesThreeAndCountsTheRest() {
        assertThat(DailyDigest.of(List.of("A", "B", "C", "D", "E", "F")))
                .get()
                .extracting(DailyDigest::body)
                .isEqualTo("A, B, C, +3 more");
    }

    /// **The one shape Angular's service worker can read**, asserted key by key
    /// ([#63](https://github.com/stainii/task/issues/63)).
    ///
    /// `ngsw-worker.js` handles the `push` event itself and returns without showing anything unless
    /// the payload has a `notification` object with a `title`. A digest serialized as its own three
    /// fields therefore encrypts, signs, delivers, and displays **nothing at all** — the device is
    /// registered, the push service returns `201`, the log says *Notified 1 of 1 device(s)*, and the
    /// phone stays dark. Not one existing test could see that: `DailyPushIntegrationTest` asserts
    /// what a *push service* receives, and a push service does not read the plaintext.
    ///
    /// Written down as an envelope rather than trusted, because it is another guarantee that lives
    /// in our code and is broken by something outside it — here, a contract owned by a generated
    /// file we never wrote.
    @Test
    void ridesInTheEnvelopeAngularsServiceWorkerReads() {
        var payload = DailyDigest.of(List.of("Vacuum the house")).orElseThrow().asServiceWorkerPayload();

        assertThat(payload).containsOnlyKeys("notification");
        assertThat(payload.get("notification")).isEqualTo(Map.of(
                "title", "Due today",
                "body", "Vacuum the house",
                // Tapping it opens the overview (ADR-0012): ADR-0006's always-visible band *is* the
                // list the notification is about. `onActionClick` is ngsw's own vocabulary for that.
                "data", Map.of("onActionClick", Map.of(
                        "default", Map.of("operation", "openWindow", "url", "/")))));
    }
}
