package be.stijnhooft.task.backend.template.util;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.Optional;

public class DateTimeUtils {

    public static Optional<LocalDate> addDaysTo(@Nullable LocalDate base, @Nullable Integer days) {
        if (base == null || days == null) {
            return Optional.empty();
        } else {
            return Optional.of(base.plusDays(days));
        }
    }
}
