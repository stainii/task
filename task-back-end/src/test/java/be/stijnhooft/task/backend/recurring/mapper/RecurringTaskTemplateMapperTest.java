package be.stijnhooft.task.backend.recurring.mapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static be.stijnhooft.task.backend.recurring.mother.RecurringTaskTemplateDtoMother.createRandomRecurringTaskTemplateDto;
import static org.assertj.core.api.Assertions.assertThat;

class RecurringTaskTemplateMapperTest {

    private final RecurringTaskTemplateMapper mapper = new RecurringTaskTemplateMapperImpl();

    @Test
    void toDomainShouldFillInDefaultCreationDate() {
        var randomRecurringTaskTemplateDto = createRandomRecurringTaskTemplateDto();
        var recurringTaskTemplate = mapper.toDomain(randomRecurringTaskTemplateDto);
        assertThat(recurringTaskTemplate.getCreationDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void toDomainShouldFillInDefaultEmptyListForExecutions() {
        var randomRecurringTaskTemplateDto = createRandomRecurringTaskTemplateDto();
        var recurringTaskTemplate = mapper.toDomain(randomRecurringTaskTemplateDto);
        assertThat(recurringTaskTemplate.getExecutions())
                .isNotNull()
                .isEmpty();
    }
}
