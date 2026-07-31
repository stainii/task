package be.stijnhooft.task.backend.task.mapper;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.dto.CreateTaskDto;
import be.stijnhooft.task.backend.task.dto.TaskDto;
import org.mapstruct.Mapper;
import org.springframework.stereotype.Component;

@Component
@Mapper(componentModel = "spring")
public interface TaskMapper {

    TaskDto toDto(Task task);

    default Task toDomain(CreateTaskDto taskDto) {
        var builder = Task.builderForInitialTask()
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
