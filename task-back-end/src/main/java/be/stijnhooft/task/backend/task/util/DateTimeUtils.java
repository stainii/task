package be.stijnhooft.task.backend.task.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

/// Parked by #10 (docs/quality-bar.md): these methods return null for null input inside a
/// @NullMarked package, and parseAsLocalDate then dereferences it. A real latent NPE, kept
/// visible here because ADR-0004 moves this parsing into the patch fold.
@SuppressWarnings("NullAway")
public class DateTimeUtils {

    /// `yyyy-DDD` is the ISO ordinal date, and it sits in its own optional block rather than
    /// alongside `MM` - so the warning is a false positive and the pattern is correct.
    ///
    /// The name matters: this used to read `SuspiciousDateFormat`, which is not a check that
    /// fires here, so for the life of this file the suppression suppressed nothing. Nobody
    /// noticed, because nothing was running Error Prone to notice with (#10).
    @SuppressWarnings("MisusedDayOfYear")
    private static final DateTimeFormatter LOOSE_ISO_DATE_TIME_ZONE_PARSER = DateTimeFormatter.ofPattern(
            "[yyyyMMdd][yyyy-MM-dd][yyyy-DDD]['T'[HHmmss][HHmm][HH:mm:ss][HH:mm][.SSSSSSSSS][.SSSSSSSS][.SSSSSSS][.SSSSSS][.SSSSS][.SSSS][.SSS][.SS][.S]][OOOO][O][z][XXXXX][XXXX]['['VV']']");

    public static ZonedDateTime parseAsZonedDateTime(String input) {
        if (input == null) {
            return null;
        }

        TemporalAccessor temporalAccessor = LOOSE_ISO_DATE_TIME_ZONE_PARSER.parseBest(
                input, ZonedDateTime::from, LocalDateTime::from, LocalDate::from);

        if (temporalAccessor instanceof ZonedDateTime time) {
            return time;
        }

        if (temporalAccessor instanceof LocalDateTime time) {
            return time
                    .atZone(ZoneId.systemDefault());
        }

        return ((LocalDate) temporalAccessor).atStartOfDay(ZoneId.systemDefault());
    }

    public static LocalDateTime parseAsLocalDateTime(String input) {
        if (input == null) {
            return null;
        }

        return parseAsZonedDateTime(input)
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    public static LocalDate parseAsLocalDate(String input) {
        return parseAsLocalDateTime(input).toLocalDate();
    }
}
