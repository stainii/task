package be.stijnhooft.task.backend.recurring.mapper;

import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import be.stijnhooft.task.backend.recurring.dto.RecurringTaskTemplateDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@Mapper(componentModel = "spring")
public interface RecurringTaskTemplateMapper {

    RecurringTaskTemplateDto toDto(RecurringTaskTemplate recurringTaskTemplate);

    Iterable<RecurringTaskTemplateDto> toDtos(Iterable<RecurringTaskTemplate> recurringTaskTemplate);

    /// The creation date is a parameter, not a field default on the entity: it comes from the
    /// Clock bean so that a template created just before midnight is dated in this
    /// application's zone rather than the host's (#44).
    @Mapping(target = "creationDate", source = "creationDate")
    @Mapping(target = "executions", ignore = true)
    RecurringTaskTemplate toDomain(RecurringTaskTemplateDto recurringTaskTemplateDto, LocalDate creationDate);

    RecurringTaskTemplate updateDomain(RecurringTaskTemplateDto recurringTaskTemplateDto, @MappingTarget RecurringTaskTemplate recurringTaskTemplate);

}
