package be.stijnhooft.task.backend.migration;

import be.stijnhooft.task.backend.migration.diff.Cause;
import be.stijnhooft.task.backend.migration.diff.Difference;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
    private final List<Difference> differences = new ArrayList<>();

    /// One field of one task where portal's document and the fold disagree, already attributed to
    /// a cause by [be.stijnhooft.task.backend.migration.diff.StoredVersusFolded].
    public void difference(Difference difference) {
        differences.add(difference);
    }

    /// Every difference, ordered by task id so that two runs can be `diff`ed — the property the fix
    /// loop needs: change the mapping, re-run, and see that six unexplained differences became
    /// zero rather than five plus a new one somewhere else.
    public List<Difference> differences() {
        return differences.stream()
                .sorted(Comparator.comparing(Difference::taskId).thenComparing(Difference::field))
                .toList();
    }

    public List<Difference> unexplained() {
        return differences().stream().filter(difference -> difference.cause() == Cause.UNEXPLAINED).toList();
    }

    public List<Difference> escalated() {
        return differences().stream().filter(Difference::isEscalated).toList();
    }

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
        renderTheVerdict(report);
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

    /// **The first page.** ADR-0005: the importer never aborts on the diff — it prints what it could
    /// not explain, and a person decides whether to continue or to change the mapping and re-run.
    /// So the two things a person must actually adjudicate go above the arithmetic, not below it,
    /// and the explained thousands are one line each.
    private void renderTheVerdict(StringBuilder report) {
        if (differences.isEmpty()) {
            return;
        }

        var escalated = escalated();
        var unexplained = unexplained();

        report.append("Stored versus folded\n--------------------\n\n");
        report.append("  %-46s %,10d%n".formatted("differences", differences.size()));
        report.append("  %-46s %,10d%n".formatted("  UNEXPLAINED - read these", unexplained.size()));
        byCause().forEach((cause, howMany) ->
                report.append("  %-46s %,10d%n".formatted("  " + cause, howMany)));

        if (!escalated.isEmpty()) {
            report.append("\n  !! %d open task(s) whose status portal and the fold disagree about.%n"
                    .formatted(escalated.size()));
            report.append("     These are the tasks you wake up to on cutover morning.\n");
            escalated.forEach(difference -> report.append("     - ").append(line(difference)).append('\n'));
        }

        if (!unexplained.isEmpty()) {
            report.append("\n  Unexplained differences, in full:\n");
            unexplained.forEach(difference -> report.append("     - ").append(line(difference)).append('\n'));
        }
        report.append('\n');
    }

    private Map<Cause, Long> byCause() {
        return differences.stream()
                .filter(difference -> difference.cause() != Cause.UNEXPLAINED)
                .collect(Collectors.groupingBy(Difference::cause, TreeMap::new, Collectors.counting()));
    }

    private static String line(Difference difference) {
        return "%s %s: stored %s, folded %s".formatted(
                difference.taskId(), difference.field(),
                quoted(difference.stored()), quoted(difference.folded()));
    }

    private static String quoted(@Nullable String value) {
        return value == null ? "(none)" : "'" + value + "'";
    }

    /// The machine-readable sidecar: one row per difference, so that a session opened *later* to fix
    /// an unexplained one starts by opening a file rather than by restoring 5 MB of Mongo.
    public String renderCsv() {
        var csv = new StringBuilder("taskId,field,cause,openTask,stored,folded\n");
        differences().forEach(difference -> csv.append("%s,%s,%s,%s,%s,%s%n".formatted(
                difference.taskId(),
                difference.field(),
                difference.cause(),
                difference.openTask(),
                escaped(difference.stored()),
                escaped(difference.folded()))));
        return csv.toString();
    }

    /// Task names and descriptions are free text written by a person over six years: they contain
    /// commas, quotes and newlines, and one unescaped newline turns the sidecar into a file that
    /// silently parses to the wrong number of rows.
    private static String escaped(@Nullable String value) {
        return value == null ? "" : '"' + value.replace("\"", "\"\"") + '"';
    }
}
