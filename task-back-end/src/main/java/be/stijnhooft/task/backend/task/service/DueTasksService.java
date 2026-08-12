package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.DueTasks;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.repository.DueTaskQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/// `task`'s side of the day's-work port. The interface is in the base package because it is the
/// module's exposed API; this, like every other implementation, is not.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DueTasksService implements DueTasks {

    /// **Most important first, then alphabetical.** It decides which names survive the truncation
    /// into *"+2 more"*, so it is a real rule rather than presentation: the point of naming tasks in
    /// the notification at all is to let the shade be judged without opening the app, and that only
    /// works if the names shown are the ones that would change what you do next.
    ///
    /// Alphabetical second so the same day always reads the same way — a list that reshuffles
    /// between the notification and the overview reads as two different lists.
    private static final Comparator<DueTaskQueries.DueTask> MOST_IMPORTANT_FIRST =
            Comparator.comparing(DueTaskQueries.DueTask::importance, Comparator.comparingInt(Importance::ordinal).reversed())
                    .thenComparing(DueTaskQueries.DueTask::name);

    private final DueTaskQueries queries;

    @Override
    public List<String> namesOfTasksDueOn(LocalDate date) {
        return queries.openTasksDueOn(date).stream()
                .sorted(MOST_IMPORTANT_FIRST)
                .map(DueTaskQueries.DueTask::name)
                .toList();
    }
}
