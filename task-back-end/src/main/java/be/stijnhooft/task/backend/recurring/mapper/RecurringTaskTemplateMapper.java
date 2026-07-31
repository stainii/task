package be.stijnhooft.task.backend.recurring.mapper;

import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import be.stijnhooft.task.backend.recurring.dto.RecurringTaskTemplateDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.stereotype.Component;

@Component
@Mapper(componentModel = "spring")
public interface RecurringTaskTemplateMapper {

    RecurringTaskTemplateDto toDto(RecurringTaskTemplate recurringTaskTemplate);

    Iterable<RecurringTaskTemplateDto> toDtos(Iterable<RecurringTaskTemplate> recurringTaskTemplate);

    @Mapping(target = "creationDate", ignore = true)
    @Mapping(target = "executions", ignore = true)
    RecurringTaskTemplate toDomain(RecurringTaskTemplateDto recurringTaskTemplateDto);

    RecurringTaskTemplate updateDomain(RecurringTaskTemplateDto recurringTaskTemplateDto, @MappingTarget RecurringTaskTemplate recurringTaskTemplate);

}
