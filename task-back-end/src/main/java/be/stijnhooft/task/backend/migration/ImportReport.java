package be.stijnhooft.task.backend.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// The arithmetic, and the things the importer refuses to decide.
///
/// [ADR-0005](../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md) makes the
/// report **the cutover abort mechanism** — "cutover can be called off on the day, on its evidence.
/// That is the abort mechanism; there is no other." This is part one of it: the counts, and the
/// lists of what could not be resolved. The field-by-field diff of stored versus folded state is
/// [#53](https://github.com/stainii/task/issues/53)'s.
///
/// Everything here is **counted, never resolved silently**. An ambiguous execution is reported and
/// left alone; a task whose template is gone is counted and imported with a null link.
public class ImportReport {

    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final Map<String, List<String>> notes = new TreeMap<>();

    public void count(String what, long howMany) {
        counts.merge(what, howMany, Long::sum);
    }

    public void increment(String what) {
        count(what, 1);
    }

    /// A thing a human has to look at. Kept as a list rather than a count because "which ones" is
    /// the only useful form of this question.
    public void note(String category, String detail) {
        notes.computeIfAbsent(category, key -> new ArrayList<>()).add(detail);
    }

    public long get(String what) {
        return counts.getOrDefault(what, 0L);
    }

    public List<String> notes(String category) {
        return List.copyOf(notes.getOrDefault(category, List.of()));
    }

    public String render() {
        var report = new StringBuilder("Portal import report\n====================\n\n");
        counts.forEach((what, howMany) -> report.append("  %-46s %,10d%n".formatted(what, howMany)));
        if (!notes.isEmpty()) {
            report.append("\nNeeds review\n------------\n");
            notes.forEach((category, details) -> {
                report.append("\n  %s (%d)%n".formatted(category, details.size()));
                // Everything matters here, but a report nobody can read is not evidence. The full
                // lists reach the file #53 writes; this is the console summary.
                details.stream().limit(20).forEach(detail -> report.append("    - ").append(detail).append('\n'));
                if (details.size() > 20) {
                    report.append("    … and %d more%n".formatted(details.size() - 20));
                }
            });
        }
        return report.toString();
    }
}
