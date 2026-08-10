package be.stijnhooft.task.backend.recurring.mapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static be.stijnhooft.task.backend.recurring.mother.RecurringTaskTemplateDtoMother.createRandomRecurringTaskTemplateDto;
import static org.assertj.core.api.Assertions.assertThat;

class RecurringTaskTemplateMapperTest {

    private final RecurringTaskTemplateMapper mapper = new RecurringTaskTemplateMapperImpl();

    /// The creation date arrives from the caller's Clock bean (#44) instead of being read off
    /// the machine inside the entity, so this asserts on a date that is nobody's "today".
    @Test
    void toDomainShouldTakeItsCreationDateFromTheCaller() {
        var randomRecurringTaskTemplateDto = createRandomRecurringTaskTemplateDto();
        var creationDate = LocalDate.of(2026, 3, 29);

        var recurringTaskTemplate = mapper.toDomain(randomRecurringTaskTemplateDto, creationDate);

        assertThat(recurringTaskTemplate.getCreationDate()).isEqualTo(creationDate);
    }

    @Test
    void toDomainShouldFillInDefaultEmptyListForExecutions() {
        var randomRecurringTaskTemplateDto = createRandomRecurringTaskTemplateDto();
        var recurringTaskTemplate = mapper.toDomain(randomRecurringTaskTemplateDto, LocalDate.of(2026, 3, 29));
        assertThat(recurringTaskTemplate.getExecutions())
                .isNotNull()
                .isEmpty();
    }
}
