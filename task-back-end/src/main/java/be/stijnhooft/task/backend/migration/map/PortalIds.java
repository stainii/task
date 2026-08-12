package be.stijnhooft.task.backend.migration.map;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/// Portal ids that are not UUIDs, and the new model's columns that insist they are.
///
/// [ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md) §241
/// predicted one case — *"`Task.undoPatch` sets no id, so undo patches in production carry Mongo
/// `ObjectId`s rather than UUIDs and need minting"* — and then claimed **"everything else is already
/// a UUID"**. Measured against the frozen archive, that claim is false, and the predicted case is
/// mis-explained:
///
/// - **115 patch ids are Mongo `ObjectId`s**, as predicted — but they are ordinary `name`,
///   `dueDateTime` and `status` edits, not undos. The shape was right, the reason was not.
/// - **11 task ids are not UUIDs at all**: `Health-1`, `Housagotchi-52`, … and one literal
///   `Healthy`. These are portal's earliest generated tasks, from before the UUID scheme, and their
///   id *is* the `flowId` — they carry no `flowId` field.
///
/// ### Minted deterministically, never randomly
///
/// A random UUID would be a different id on every run, which breaks the one property ADR-0005
/// demands of the whole importer: **re-runnable and idempotent, so dry runs are free**. Two dry runs
/// would disagree about 126 rows, and a diff between them would be noise.
///
/// So the id is derived from the portal id by name-based UUID: the same string always yields the
/// same UUID, on this run and on a repair run in month three. The mapping is one-way but the
/// *lookup* is not — the diff report records every minted pair
/// ([#53](https://github.com/stainii/task/issues/53)), which is what makes a later repair able to
/// find the row again.
public final class PortalIds {

    /// Namespaced, so a task and a patch that somehow shared a portal id could not collide, and so
    /// a minted id can never coincide with one minted for some other kind of thing later.
    private static final String TASK = "portal:task:";
    private static final String PATCH = "portal:patch:";
    private static final String TEMPLATE = "portal:template:";
    private static final String DEFINITION = "portal:definition:";

    private PortalIds() {
    }

    /// The 11,844 UUID-shaped task ids pass through untouched; the 11 others are minted.
    public static UUID ofTask(String portalId) {
        return parseOrMint(TASK, portalId);
    }

    /// The 38,096 UUID-shaped patch ids pass through untouched; the 115 `ObjectId`s are minted.
    public static UUID ofPatch(String portalId) {
        return parseOrMint(PATCH, portalId);
    }

    /// A recurring task's identity is `<deployment>-<id>`, not the bare number: the four databases
    /// number their rows independently, so `1` names a different chore in each.
    public static UUID ofRecurringTask(String deployment, String recurringTaskId) {
        return mint(TEMPLATE, deployment + "-" + recurringTaskId);
    }

    /// Portal's `taskTemplate` documents are keyed by Mongo `ObjectId`, so all three are minted.
    public static UUID ofTaskTemplate(String objectId) {
        return mint(TEMPLATE, objectId);
    }

    /// Portal's task definitions are embedded documents with no id of their own, so identity is
    /// position within the template — stable as long as the archive is, which it is: it is frozen.
    public static UUID ofDefinition(UUID templateId, int index) {
        return mint(DEFINITION, templateId + "#" + index);
    }

    /// A task synthesised from an `execution` row that no migrated task accounts for. Its identity
    /// is the execution's, so a re-run produces the same task rather than a second one.
    public static UUID ofSynthesisedTask(String deployment, long executionId) {
        return mint(TASK, "execution:" + deployment + "-" + executionId);
    }

    /// The two patches a synthesised task is folded from. `role` is `create` or `complete`, so both
    /// are derived from the execution and neither is random.
    public static UUID ofSynthesisedPatch(String deployment, long executionId, String role) {
        return mint(PATCH, "execution:" + deployment + "-" + executionId + ":" + role);
    }

    /// Strict, deliberately. `UUID.fromString` accepts plenty that is not a UUID — `1-2-3-4-5`
    /// parses happily — so trusting it to reject would let a malformed portal id through as a
    /// *different* UUID than minting would produce, and only for some values. The canonical form is
    /// the whole test.
    private static final java.util.regex.Pattern CANONICAL_UUID = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static UUID parseOrMint(String namespace, String portalId) {
        return CANONICAL_UUID.matcher(portalId).matches()
                ? UUID.fromString(portalId)
                : mint(namespace, portalId);
    }

    private static UUID mint(String namespace, String portalId) {
        return UUID.nameUUIDFromBytes((namespace + portalId).getBytes(StandardCharsets.UTF_8));
    }
}
