package be.stijnhooft.task.backend.recurring;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecurringTaskTemplateTest {

    @Test
    void constructorWhenMinNumberOfDaysBetweenExecutionsIsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new RecurringTaskTemplate(UUID.randomUUID(), "test", -1, 2));
    }

    @Test
    void constructorWhenMinNumberOfDaysBetweenExecutionsIsZero() {
        assertThrows(IllegalArgumentException.class, () -> new RecurringTaskTemplate(UUID.randomUUID(), "test", 0, 2));
    }

    @Test
    void constructorWhenMaxNumberOfDaysBetweenExecutionsIsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new RecurringTaskTemplate(UUID.randomUUID(), "test", 1, -2));
    }

    @Test
    void constructorWhenMaxNumberOfDaysBetweenExecutionsIsZero() {
        assertThrows(IllegalArgumentException.class, () -> new RecurringTaskTemplate(UUID.randomUUID(), "test", 1, 0));
    }

    @Test
    void constructorWhenMaxNumberOfDaysBetweenExecutionsIsSmallerThanMinNumberOfDaysBetweenExecutions() {
        assertThrows(IllegalArgumentException.class, () -> new RecurringTaskTemplate(UUID.randomUUID(), "test", 3, 2));
    }

    @Test
    void constructorWhenMaxNumberOfDaysBetweenExecutionsIsEqualsToMinNumberOfDaysBetweenExecutions() {
        var recurringTaskTemplate = new RecurringTaskTemplate(UUID.randomUUID(), "test", 3, 3);

        assertEquals("test", recurringTaskTemplate.getName());
        assertEquals(3, recurringTaskTemplate.getMinNumberOfDaysBetweenExecutions());
        assertEquals(3, recurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions());    }

    @Test
    void constructorWhenSuccess() {
        var recurringTaskTemplate = new RecurringTaskTemplate(UUID.randomUUID(), "test", 3, 5);

        assertEquals("test", recurringTaskTemplate.getName());
        assertEquals(3, recurringTaskTemplate.getMinNumberOfDaysBetweenExecutions());
        assertEquals(5, recurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions());
    }

    @Test
    void updateWhenMinNumberOfDaysBetweenExecutionsIsNegative() {
        assertThrows(IllegalArgumentException.class, () ->
                new RecurringTaskTemplate().update("test", -1, 2));
    }

    @Test
    void updateWhenMinNumberOfDaysBetweenExecutionsIsZero() {
        assertThrows(IllegalArgumentException.class, () ->
                new RecurringTaskTemplate().update("test", 0, 2));
    }

    @Test
    void updateWhenMaxNumberOfDaysBetweenExecutionsIsNegative() {
        assertThrows(IllegalArgumentException.class, () ->
                new RecurringTaskTemplate().update("test", 1, -2));
    }

    @Test
    void updateWhenMaxNumberOfDaysBetweenExecutionsIsZero() {
        assertThrows(IllegalArgumentException.class, () ->
                new RecurringTaskTemplate().update("test", 1, 0));
    }

    @Test
    void updateWhenMaxNumberOfDaysBetweenExecutionsIsSmallerThanMinNumberOfDaysBetweenExecutions() {
        assertThrows(IllegalArgumentException.class, () ->
                new RecurringTaskTemplate().update("test", 3, 2));
    }

    @Test
    void updateWhenMaxNumberOfDaysBetweenExecutionsIsEqualsToMinNumberOfDaysBetweenExecutions() {
        var recurringTaskTemplate = new RecurringTaskTemplate(UUID.randomUUID(), "testietest", 1, 2);
        recurringTaskTemplate.update("test", 3, 3);

        assertEquals("test", recurringTaskTemplate.getName());
        assertEquals(3, recurringTaskTemplate.getMinNumberOfDaysBetweenExecutions());
        assertEquals(3, recurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions());
    }

    @Test
    void updateWhenSuccess() {
        var recurringTaskTemplate = new RecurringTaskTemplate(UUID.randomUUID(), "testietest", 1, 2);
        recurringTaskTemplate.update("test", 3, 5);

        assertEquals("test", recurringTaskTemplate.getName());
        assertEquals(3, recurringTaskTemplate.getMinNumberOfDaysBetweenExecutions());
        assertEquals(5, recurringTaskTemplate.getMaxNumberOfDaysBetweenExecutions());
    }


}

