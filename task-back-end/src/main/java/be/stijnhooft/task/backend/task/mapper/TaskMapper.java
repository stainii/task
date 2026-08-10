package be.stijnhooft.task.backend.task.mapper;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.dto.CreateTaskDto;
import be.stijnhooft.task.backend.task.dto.TaskDto;
import org.mapstruct.Mapper;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@Mapper(componentModel = "spring")
public interface TaskMapper {

    TaskDto toDto(Task task);

    /// The clock is a parameter because a task that the request does not date is dated now,
    /// and *now* belongs to the Clock bean rather than to the machine (#44).
    default Task toDomain(CreateTaskDto taskDto, Clock clock) {
        var builder = Task.builderForInitialTask(clock)
                .name(taskDto.name())
                .description(taskDto.description())
                .importance(taskDto.importance())
                .context(taskDto.context())
                .dueDate(taskDto.dueDate());

        if (taskDto.status() != null) {
            builder.status(taskDto.status());
        }

        if (taskDto.creationDateTime() != null) {
            builder.creationDateTime(taskDto.creationDateTime());
        }

        if (taskDto.startDate() != null) {
            builder.startDate(taskDto.startDate());
        }

        return builder.build();
    }
}
