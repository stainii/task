package be.stijnhooft.task.backend.task.mapper;

import be.stijnhooft.task.backend.task.domain.TaskPatch;
import be.stijnhooft.task.backend.task.dto.TaskPatchDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.stereotype.Component;

@Component
@Mapper(componentModel = "spring")
public interface TaskPatchMapper {

    /// `sequence` is deliberately unmapped **inbound**: it is the server's clock, assigned on
    /// receipt, and a client that could set it could rewrite another client's cursor. It is mapped
    /// outbound, where it is the number the client's cursor is made of.
    @Mapping(target = "sequence", ignore = true)
    TaskPatch toDomain(TaskPatchDto taskPatchDto);

    TaskPatchDto toDto(TaskPatch taskPatch);
}
