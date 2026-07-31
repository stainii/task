package be.stijnhooft.task.backend.utils;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

public class DateTimeUtils {

    @SuppressWarnings("SuspiciousDateFormat")
    private static final DateTimeFormatter LOOSE_ISO_DATE_TIME_ZONE_PARSER = DateTimeFormatter.ofPattern(
            "[yyyyMMdd][yyyy-MM-dd][yyyy-DDD]['T'[HHmmss][HHmm][HH:mm:ss][HH:mm][.SSSSSSSSS][.SSSSSSSS][.SSSSSSS][.SSSSSS][.SSSSS][.SSSS][.SSS][.SS][.S]][OOOO][O][z][XXXXX][XXXX]['['VV']']");

    public static Optional<LocalDate> addDaysTo(@Nullable LocalDate base, @Nullable Integer days) {
        if (base == null || days == null) {
            return Optional.empty();
        } else {
            return Optional.of(base.plusDays(days));
        }
    }

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
