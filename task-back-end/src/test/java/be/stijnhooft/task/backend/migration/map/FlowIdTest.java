package be.stijnhooft.task.backend.migration.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The three wrong parses of a `flowId`, each held down by the archive value that exposes it.
///
/// All of them fail **silently**: they return a plausible answer, which is how the first-hyphen
/// split reached ADR-0005's own amendment as a measurement of the data.
class FlowIdTest {

    /// The four deployment names, exactly as `todo.subscription.origin` spells them — three
    /// capitalised, one not, none equal to its database name.
    private final FlowId flowIds = new FlowId(List.of("Housagotchi", "Health", "Setlist", "social-recurring-tasks"));

    @ParameterizedTest(name = "{0} → {1} / {2}")
    @CsvSource({
            "Housagotchi-52, Housagotchi, 52",
            "Health-1, Health, 1",
            "Setlist-402, Setlist, 402"})
    void aDeploymentFlowIdSplitsIntoItsDeploymentAndTask(String flowId, String deployment, String taskId) {
        assertThat(flowIds.parse(flowId)).contains(new FlowId.Parsed(deployment, taskId));
    }

    /// **Wrong parse 1: splitting on the first hyphen.** `social-recurring-tasks` has hyphens inside
    /// the prefix, so the naive split invents a deployment called `social` — 764 tasks' worth.
    @Test
    void aPrefixContainingHyphensIsNotSplitAtTheFirstOne() {
        assertThat(flowIds.parse("social-recurring-tasks-7"))
                .contains(new FlowId.Parsed("social-recurring-tasks", "7"));
    }

    /// **Wrong parse 2: splitting on the last hyphen.** Every one of the 746 `Todo-<uuid>` values
    /// would become its own deployment — 750 distinct "deployments" in a system that had four.
    @ParameterizedTest
    @ValueSource(strings = {
            "Todo-8b82cfe7-5063-4af7-be29-22d62cbbf635",
            "Todo-002695e0-361f-4027-8d00-62579b0e1287"})
    void aTodoFlowIdNamesNoDeployment(String flowId) {
        assertThat(flowIds.parse(flowId)).isEmpty();
        assertThat(FlowId.isTodo(flowId)).isTrue();
    }

    /// **Wrong parse 3: last hyphen only when the tail is numeric** — the repair that looks
    /// sufficient. Exactly one task in 11,855 defeats it, because its UUID's final segment is all
    /// digits. Matching known prefixes never asks the question.
    @Test
    void aTodoUuidEndingInDigitsIsStillNotADeployment() {
        var trap = "Todo-f660a98a-5fa5-4393-a614-770444105216";

        assertThat(flowIds.parse(trap)).isEmpty();
        assertThat(FlowId.isTodo(trap)).isTrue();
    }

    /// Longest first, so a deployment name that is a prefix of another cannot swallow it. Nothing in
    /// the archive needs this today; it is the property that makes the rule safe to keep.
    @Test
    void theLongestMatchingPrefixWins() {
        var overlapping = new FlowId(List.of("social", "social-recurring-tasks"));

        assertThat(overlapping.parse("social-recurring-tasks-7"))
                .contains(new FlowId.Parsed("social-recurring-tasks", "7"));
    }

    @Test
    void aHandMadeTaskHasNoFlowIdAtAll() {
        assertThat(flowIds.parse(null)).isEmpty();
        assertThat(FlowId.isTodo(null)).isFalse();
    }

    /// A prefix that matches but whose suffix is not a recurring task's id is not a match. Every one
    /// of the archive's 8,086 real suffixes is numeric.
    @ParameterizedTest
    @ValueSource(strings = {"Health-", "Health-abc", "Healthy"})
    void aPrefixWithoutANumericTaskIdIsNotAMatch(String flowId) {
        assertThat(flowIds.parse(flowId)).isEmpty();
    }
}
