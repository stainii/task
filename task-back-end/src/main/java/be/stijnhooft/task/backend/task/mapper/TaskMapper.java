package be.stijnhooft.task.backend.task.mapper;

import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.dto.TaskDto;
import org.mapstruct.Mapper;
import org.springframework.stereotype.Component;

/// One direction only. There is no `toDomain`, because there is no whole-task request body to map
/// from any more: a client writes patches, and the first patch for a task id builds the task through
/// the fold rather than through a mapper (ADR-0004).
@Component
@Mapper(componentModel = "spring")
public interface TaskMapper {

    TaskDto toDto(Task task);
}
