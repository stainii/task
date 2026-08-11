package be.stijnhooft.task.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// D7's fix, proven against output the real modulith cannot produce yet: with one arrow between two
/// modules there is nothing to reorder, so these diagrams are written by hand.
class ModuleDocumentationTest {

    @TempDir
    Path folder;

    /// The defect itself: one graph, written twice with its arrows in different orders, must land
    /// on one file.
    @Test
    void theSameArrowsInAnyOrderProduceTheSameFile() throws IOException {
        var oneWay = diagram("first.puml", "Rel(A, B, \"x\")", "Rel(B, C, \"x\")");
        var theOther = diagram("second.puml", "Rel(B, C, \"x\")", "Rel(A, B, \"x\")");

        ModuleDocumentation.sortGeneratedLists(folder);

        assertThat(Files.readString(oneWay)).isEqualTo(Files.readString(theOther));
    }

    /// A new arrow still shows up, which is the entire point of committing the diagram: a sort that
    /// hid one would have traded a noisy channel for a silent one.
    @Test
    void anAddedArrowStillChangesTheFile() throws IOException {
        var two = diagram("two.puml", "Rel(A, B, \"x\")", "Rel(B, C, \"x\")");
        var three = diagram("three.puml", "Rel(B, C, \"x\")", "Rel(A, B, \"x\")", "Rel(C, D, \"x\")");

        ModuleDocumentation.sortGeneratedLists(folder);

        assertThat(Files.readString(three)).isNotEqualTo(Files.readString(two));
    }

    /// Only the arrows move. Sorting the whole file would put `@enduml` in the middle of it.
    @Test
    void everythingThatIsNotAListKeepsItsPlace() throws IOException {
        var diagram = diagram("components.puml", "Rel(B, C, \"x\")", "Rel(A, B, \"x\")");

        ModuleDocumentation.sortGeneratedLists(folder);

        assertThat(Files.readAllLines(diagram))
                .containsExactly("@startuml", "title Modules", "Rel(A, B, \"x\")", "Rel(B, C, \"x\")", "@enduml");
    }

    /// Two lists in one file stay two lists: a run is sorted where it sits.
    @Test
    void aSecondListIsNotMergedIntoTheFirst() throws IOException {
        var doc = write("module-task.adoc",
                "|Spring components", "* `b`", "* `a`", "|Events listened to", "* `d`", "* `c`");

        ModuleDocumentation.sortGeneratedLists(folder);

        assertThat(Files.readAllLines(doc))
                .containsExactly("|Spring components", "* `a`", "* `b`", "|Events listened to", "* `c`", "* `d`");
    }

    /// A file already in order is not rewritten at all - including its missing final newline.
    /// `Documenter` writes none, and adding one would itself be a diff on every other run.
    @Test
    void aFileAlreadyInOrderIsLeftExactlyAsItWas() throws IOException {
        var diagram = diagram("stable.puml", "Rel(A, B, \"x\")", "Rel(B, C, \"x\")");
        var before = Files.readString(diagram);

        ModuleDocumentation.sortGeneratedLists(folder);

        assertThat(Files.readString(diagram)).isEqualTo(before).doesNotEndWith("\n");
    }

    private Path diagram(String name, String... arrows) throws IOException {
        var lines = new ArrayList<>(List.of("@startuml", "title Modules"));
        lines.addAll(List.of(arrows));
        lines.add("@enduml");
        return write(name, lines.toArray(String[]::new));
    }

    private Path write(String name, String... lines) throws IOException {
        return Files.writeString(folder.resolve(name), String.join("\n", lines));
    }
}
