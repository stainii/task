package be.stijnhooft.task.backend.task.mother;

import be.stijnhooft.task.backend.task.dto.TaskPatchDto;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

public class TaskPatchDtoMother {

    public static TaskPatchDto createRandomTaskPatchDto() {
        return createRandomTaskPatchDto(Map.of("name", "name " + UUID.randomUUID()));
    }

    public static TaskPatchDto createRandomTaskPatchDto(Map<String, String> changes) {
        return new TaskPatchDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                null,
                changes);
    }
}
