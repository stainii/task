package be.stijnhooft.task.backend.task;

import org.jspecify.annotations.NonNull;

import java.util.List;

public record TaskCreationRequestedEvent(
        @NonNull List<Task> tasks
) {
}
