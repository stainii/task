package be.stijnhooft.task.backend.task.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record TaskPatchDto(UUID taskId, LocalDateTime dateTime, Map<String, String> changes) {
}
