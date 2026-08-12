package be.stijnhooft.task.backend.migration.map;

import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// Portal's `flowId` — **dropped from the model, confirmed dead, and alive in the source data as
/// the only provenance link that exists**
/// ([ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md)).
///
/// `portal-recurring-tasks` built it as `<deploymentName>-<recurringTaskId>` and `portal-todo`
/// copied it onto the generated task, so it is what gives a migrated task a real `taskTemplateId`,
/// retroactively, across years. It is available exactly once: after cutover the field denotes
/// nothing.
///
/// ### Both obvious parses are wrong, and both fail silently
///
/// Measured against the frozen archive's 8,832 `flowId`s:
///
/// - **Splitting on the first hyphen** invents a deployment named `social`, because
///   `social-recurring-tasks-7` puts hyphens *inside* the prefix. It looks right — it yields exactly
///   five plausible names — which is how it reached ADR-0005's own amendment as a measurement.
/// - **Splitting on the last hyphen** explodes into **750** distinct prefixes, because every one of
///   the 746 `Todo-<uuid>` values becomes its own "deployment".
/// - **Splitting on the last hyphen only when the tail is numeric** — the repair that looks
///   sufficient — is wrong for exactly one task in 11,855:
///   `Todo-f660a98a-5fa5-4393-a614-770444105216`, whose final UUID segment is all digits.
///
/// So the rule is neither: **match against the known prefixes, longest first.** Anything left is a
/// `Todo-<uuid>`, which is portal-todo's own per-task flow id and names no deployment at all — 746
/// of them, every one distinct, so it links to nothing and is simply dropped.
///
/// ### The prefix set is a table, not a derivation
///
/// ADR-0005 first told the importer to derive the set from the data because nothing else named the
/// deployments. Something else does: `todo.subscription.origin` holds exactly the four. They are
/// read from there and the derivation is kept as the cross-check — [PrefixReconciliation] is what
/// aborts when the two disagree.
public final class FlowId {

    /// Portal-todo's own per-task flow id, which is not a deployment. Excluded from the deployment
    /// set *before* the database match, because ADR-0005's step 2 treats a prefix with no
    /// recurring-tasks database as an abort signal — and this one is entirely correct.
    public static final String TODO_PREFIX = "Todo";

    private final List<String> deploymentPrefixes;

    /// @param deploymentPrefixes the deployment names, as read from `todo.subscription.origin`
    public FlowId(List<String> deploymentPrefixes) {
        // Longest first, so `social-recurring-tasks` is tried before any shorter prefix that could
        // also match. Sorting here rather than trusting the caller's order is the whole guarantee.
        this.deploymentPrefixes = deploymentPrefixes.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /// Splits a `flowId` into the deployment that produced it and the recurring task's id there.
    ///
    /// @return empty for a null `flowId` (a hand-made task), for a `Todo-<uuid>`, and for anything
    ///         matching no known prefix. The last case is not silent — [Parsed] never stands in for
    ///         it, and the caller counts what it could not parse.
    public Optional<Parsed> parse(@Nullable String flowId) {
        if (flowId == null) {
            return Optional.empty();
        }
        for (String prefix : deploymentPrefixes) {
            if (flowId.startsWith(prefix + "-")) {
                var suffix = flowId.substring(prefix.length() + 1);
                // Every one of the 8,086 real suffixes is numeric. A non-numeric one would mean a
                // deployment prefix that is itself a prefix of another, which is why this is checked
                // rather than assumed.
                if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
                    return Optional.of(new Parsed(prefix, suffix));
                }
            }
        }
        return Optional.empty();
    }

    /// True for portal-todo's own flow ids, which are correct and link to nothing.
    public static boolean isTodo(@Nullable String flowId) {
        return flowId != null && flowId.startsWith(TODO_PREFIX + "-");
    }

    /// The deployment a `flowId` names, and the id of the recurring task inside it.
    public record Parsed(String deployment, String recurringTaskId) {
    }
}
