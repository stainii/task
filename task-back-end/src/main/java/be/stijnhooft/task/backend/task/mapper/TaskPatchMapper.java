package be.stijnhooft.task.backend.task.mapper;

import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.dto.TaskPatchDto;
import org.mapstruct.Mapper;
import org.springframework.stereotype.Component;

@Component
@Mapper(componentModel = "spring")
public interface TaskPatchMapper {

    TaskPatch toDomain(TaskPatchDto taskPatchDto);
}
