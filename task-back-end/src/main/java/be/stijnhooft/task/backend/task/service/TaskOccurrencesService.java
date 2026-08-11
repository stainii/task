package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.TaskOccurrences;
import be.stijnhooft.task.backend.task.repository.TaskOccurrenceQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/// `task`'s side of the query port. The interface is in the base package because it is the module's
/// exposed API; this, like every other implementation, is not.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskOccurrencesService implements TaskOccurrences {

    private final TaskOccurrenceQueries queries;
    private final Clock clock;

    @Override
    public boolean hasOpenOccurrence(UUID templateId) {
        return queries.hasOpenTask(templateId);
    }

    @Override
    public Optional<LocalDate> lastCompletionOf(UUID templateId) {
        return queries.latestCompletedOn(templateId);
    }

    /// A creation date is an `Instant` and a firing date is a day, so the zone is applied here -
    /// from the `Clock` bean, the one place that owns it.
    @Override
    public Optional<LocalDate> lastClosureOf(UUID templateId) {
        return queries.latestClosedFiring(templateId)
                .map(firing -> LocalDate.ofInstant(firing, clock.getZone()));
    }
}
