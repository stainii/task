package be.stijnhooft.task.backend.template.mapper;

import be.stijnhooft.task.backend.template.TaskTemplate;
import be.stijnhooft.task.backend.template.dto.TaskTemplateDto;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.springframework.stereotype.Component;

@Component
@Mapper(componentModel = "spring")
public interface TaskTemplateMapper {

    TaskTemplate toDomain(TaskTemplateDto taskTemplateDto);

    TaskTemplate updateDomain(TaskTemplateDto taskTemplateDto, @MappingTarget TaskTemplate taskTemplate);

    Iterable<TaskTemplateDto> toDtos(Iterable<TaskTemplate> taskTemplates);
}
