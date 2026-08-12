package be.stijnhooft.task.backend.notification.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
