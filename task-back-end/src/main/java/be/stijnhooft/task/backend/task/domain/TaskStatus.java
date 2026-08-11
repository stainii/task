package be.stijnhooft.task.backend.task.domain;

import java.util.Arrays;

public enum TaskStatus {

    OPEN,
    COMPLETED,
    CANCELLED;

    public static TaskStatus parse(Object object) {
        final String stringToSearchFor = object.toString();

        return Arrays.stream(TaskStatus.values())
                .filter(enumValue -> stringToSearchFor.equalsIgnoreCase(enumValue.name()))
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException("Task status with name " + stringToSearchFor + " does not exist."));
    }

}
