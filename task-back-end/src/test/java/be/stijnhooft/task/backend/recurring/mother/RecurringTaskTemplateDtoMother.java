package be.stijnhooft.task.backend.recurring.mother;

import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import be.stijnhooft.task.backend.recurring.dto.RecurringTaskTemplateDto;
import org.instancio.Instancio;

import static org.instancio.Select.field;

public class RecurringTaskTemplateDtoMother {

    public static RecurringTaskTemplateDto createRandomRecurringTaskTemplateDto() {
        int minNumberOfDaysBetweenExecutions = (int) (Math.random() * 10);
        int maxNumberOfDaysBetweenExecutions = (int) (minNumberOfDaysBetweenExecutions + (Math.random() * 10));
        return Instancio.of(RecurringTaskTemplateDto.class)
                .set(field(RecurringTaskTemplateDto::minNumberOfDaysBetweenExecutions), minNumberOfDaysBetweenExecutions)
                .set(field(RecurringTaskTemplateDto::maxNumberOfDaysBetweenExecutions), maxNumberOfDaysBetweenExecutions)
                .create();
    }
}
