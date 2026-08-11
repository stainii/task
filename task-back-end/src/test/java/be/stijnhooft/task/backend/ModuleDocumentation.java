package be.stijnhooft.task.backend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/// Makes `Documenter`'s output stable, which is **D7**.
///
/// `Documenter` walks sets, so the order of the arrows it writes is not: two `Rel(...)` lines in
/// `components.puml` swapped places once in nine consecutive runs of identical code. That diagram
/// is committed precisely so a *new* arrow between modules shows up as a diff
/// ([ADR-0003](../../../../../../../docs/adr/0003-two-modules-with-package-visibility-as-the-boundary.md)),
/// and a file that rewrites itself for no reason makes that channel worthless — the noise trains
/// you to ignore the signal, and there is no second reviewer here to catch what you then wave
/// through.
///
/// **This lives apart from the test that calls it because it needs a test of its own.** Today the
/// modulith has one arrow, so nothing in the real output is out of order and the sort cannot be
/// seen to work; the symptom only returns when a third module does
/// ([#51](https://github.com/stainii/task/issues/51)). A mechanism that is dormant *and* unproven
/// is how this project's suppressions came to suppress nothing.
public final class ModuleDocumentation {

    private ModuleDocumentation() {
    }

    /// Sorts the lists whose order carries no meaning, in every file `Documenter` just wrote.
    ///
    /// Only two shapes are touched, and only where they already sit together: the `Rel(...)` arrows
    /// of a diagram and the `* ` bullets of a table cell, which come from the same kind of set and
    /// drift the same way. Everything else keeps the order `Documenter` chose.
    public static void sortGeneratedLists(Path folder) {
        try (Stream<Path> generated = Files.list(folder)) {
            for (Path file : generated.toList()) {
                var name = file.getFileName().toString();
                if (name.endsWith(".puml")) {
                    sortRunsIn(file, line -> line.startsWith("Rel("));
                } else if (name.endsWith(".adoc")) {
                    sortRunsIn(file, line -> line.startsWith("* "));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The generated module documentation could not be sorted.", e);
        }
    }

    /// Sorts every maximal run of consecutive matching lines, in place. A run rather than the whole
    /// file, so a list that appears twice stays two lists and nothing is lifted out of the block it
    /// belongs to.
    ///
    /// The file is split and rejoined on `\n` rather than read as lines, because a line reader
    /// cannot tell a final newline from its absence — and rewriting the file with one `Documenter`
    /// did not write would be a diff on every other run, which is the very thing this class exists
    /// to stop.
    static void sortRunsIn(Path file, Predicate<String> inList) throws IOException {
        var lines = List.of(Files.readString(file).split("\n", -1));
        var sorted = new ArrayList<String>(lines.size());
        var run = new ArrayList<String>();

        for (String line : lines) {
            if (inList.test(line)) {
                run.add(line);
            } else {
                flush(run, sorted);
                sorted.add(line);
            }
        }
        flush(run, sorted);

        if (!sorted.equals(lines)) {
            Files.writeString(file, String.join("\n", sorted));
        }
    }

    private static void flush(List<String> run, List<String> into) {
        run.sort(Comparator.naturalOrder());
        into.addAll(run);
        run.clear();
    }
}
