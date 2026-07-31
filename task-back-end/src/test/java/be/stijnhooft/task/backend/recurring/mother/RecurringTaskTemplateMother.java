package be.stijnhooft.task.backend.recurring.mother;

import be.stijnhooft.task.backend.recurring.RecurringTaskTemplate;
import org.instancio.Instancio;

import static org.instancio.Select.field;

public class RecurringTaskTemplateMother {

    public static RecurringTaskTemplate createRandomRecurringTaskTemplate() {
        int minNumberOfDaysBetweenExecutions = (int) (Math.random() * 10);
        int maxNumberOfDaysBetweenExecutions = (int) (minNumberOfDaysBetweenExecutions + (Math.random() * 10));
        return Instancio.of(RecurringTaskTemplate.class)
                .set(field(RecurringTaskTemplate::getMinNumberOfDaysBetweenExecutions), minNumberOfDaysBetweenExecutions)
                .set(field(RecurringTaskTemplate::getMaxNumberOfDaysBetweenExecutions), maxNumberOfDaysBetweenExecutions)
                .ignore(field(RecurringTaskTemplate::getVersion))
                .create();
    }
}
