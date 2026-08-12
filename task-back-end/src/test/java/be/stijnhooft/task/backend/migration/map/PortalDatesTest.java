package be.stijnhooft.task.backend.migration.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/quality-bar.md`: *date and comparison logic is tested at its boundaries*, and it names this
/// class's subject directly — *"does the importer's date arithmetic land on the boundary or one day
/// off?"*
///
/// It lands one day off for 9,662 of the archive's patches under the rule ADR-0005 originally
/// prescribed, so every case here is a real value shape counted in the corpus rather than an
/// invented one.
class PortalDatesTest {

    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    @Nested
    @DisplayName("a value that names an instant is read in the reader's zone")
    class Instants {

        /// Winter: `Z` is UTC+1 behind Brussels, so 23:00 UTC is midnight *the next day*.
        /// 3,567 start dates and 654 due dates in the archive sit at exactly this time.
        @Test
        void aWinterMidnightBelongsToTheDayItIsMidnightOf() {
            assertThat(PortalDates.toLocalDate("2024-03-15T23:00:00.000Z", BRUSSELS))
                    .isEqualTo(LocalDate.of(2024, 3, 16));
        }

        /// Summer: UTC+2, so 22:00 UTC is midnight the next day. 4,461 start dates and 957 due dates.
        @Test
        void aSummerMidnightBelongsToTheDayItIsMidnightOf() {
            assertThat(PortalDates.toLocalDate("2024-07-14T22:00:00.000Z", BRUSSELS))
                    .isEqualTo(LocalDate.of(2024, 7, 15));
        }

        /// The daylight-saving switch itself, from both sides. In 2024 Brussels moved on 31 March,
        /// so the offset that decides the date changes between these two values — which is exactly
        /// why the zone does the arithmetic and a fixed `plusHours` would not do.
        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource({
                "2024-03-30T23:00:00.000Z, 2024-03-31",
                "2024-03-31T22:00:00.000Z, 2024-04-01",
                "2024-10-26T22:00:00.000Z, 2024-10-27",
                "2024-10-27T23:00:00.000Z, 2024-10-28"})
        void theOffsetIsTheZonesAtThatMoment(String value, LocalDate expected) {
            assertThat(PortalDates.toLocalDate(value, BRUSSELS)).isEqualTo(expected);
        }

        /// **The defect, stated as a test.** Reading these values as written — which is what
        /// truncating in UTC does — yields the day before, silently, for every one of the 9,662.
        @ParameterizedTest
        @CsvSource({"2024-03-15T23:00:00.000Z", "2024-07-14T22:00:00.000Z"})
        void truncatingInUtcWouldLoseADay(String value) {
            var asWritten = Instant.parse(value).atZone(ZoneId.of("UTC")).toLocalDate();

            assertThat(PortalDates.toLocalDate(value, BRUSSELS))
                    .isEqualTo(asWritten.plusDays(1))
                    .isNotEqualTo(asWritten);
        }

        /// The 22 archive values that genuinely sit at midnight UTC — 01:00 or 02:00 Brussels — stay
        /// on the day they name. The rule is not "add a day"; it is "ask the zone".
        @Test
        void aMiddayInstantKeepsItsOwnDay() {
            assertThat(PortalDates.toLocalDate("2024-07-14T00:00:00.000Z", BRUSSELS))
                    .isEqualTo(LocalDate.of(2024, 7, 14));
        }
    }

    @Nested
    @DisplayName("a value that names a local time already is one")
    class LocalTimes {

        /// The Java service's shape: microsecond precision, no zone. 10,515 start dates.
        @Test
        void aLocalDateTimeIsTruncated() {
            assertThat(PortalDates.toLocalDate("2022-09-14T06:00:00.626132", BRUSSELS))
                    .isEqualTo(LocalDate.of(2022, 9, 14));
        }

        /// Minute precision, which is how 11,502 due dates are written. `2021-02-28T00:00` is
        /// already midnight local, so it must **not** move.
        @Test
        void aMinutePrecisionMidnightDoesNotMove() {
            assertThat(PortalDates.toLocalDate("2021-02-28T00:00", BRUSSELS))
                    .isEqualTo(LocalDate.of(2021, 2, 28));
        }

        /// An evening local time stays on its own day. Read as an instant it would move to the next
        /// one, which is the mirror of the defect above.
        @Test
        void anEveningLocalTimeStaysOnItsOwnDay() {
            assertThat(PortalDates.toLocalDate("2021-12-31T23:00:00.000", BRUSSELS))
                    .isEqualTo(LocalDate.of(2021, 12, 31));
        }
    }

    /// ADR-0005 refused a lenient parser: it would serve data that stops arriving the day after
    /// cutover, and eventually swallow a real bug.
    @Test
    void anUnreadableValueFailsRatherThanGuessing() {
        assertThatThrownBy(() -> PortalDates.toLocalDate("not a date", BRUSSELS))
                .isInstanceOf(DateTimeParseException.class);
    }

    /// `completedOn` is *when did I do it* — a domain value, so it is the day in Brussels.
    @Test
    void aCompletionInstantIsDatedInTheReadersZone() {
        assertThat(PortalDates.toLocalDate(Instant.parse("2024-03-15T23:30:00Z"), BRUSSELS))
                .isEqualTo(LocalDate.of(2024, 3, 16));
    }
}
