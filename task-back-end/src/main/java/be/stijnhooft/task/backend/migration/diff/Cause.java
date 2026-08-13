package be.stijnhooft.task.backend.migration.diff;

/// **The closed list of explanations we agreed to be satisfied by**, fixed before the first
/// stored-versus-folded run and recorded in
/// [ADR-0005](../../../../../../../../docs/adr/0005-migration-by-replay-into-one-history.md).
///
/// The importer does not abort on any of these. It bins every difference and prints what it could
/// not explain first; a person decides whether to continue or to change the mapping and re-run,
/// which is free because the import is idempotent.
///
/// A count of divergent tasks would carry almost no signal here — **divergence is expected**,
/// because portal's merge *is* D2 and a task whose patches arrived out of order is *supposed* to
/// fold to something other than what portal stored. Thousands of attributable differences is
/// health; one [#UNEXPLAINED] is a defect in the fold or the translation.
///
/// Nothing may be added to this list after a run. Adding a cause having seen the result is
/// precisely the move ADR-0005's *a threshold decided while looking at the result is not a
/// threshold* forbids.
public enum Cause {

    /// **D2 itself.** Two patches touch the field, and the last of them in portal's `history` array
    /// — insertion order, so arrival order — is not the last of them by `dateTime`. Portal took the
    /// last to arrive; the fold takes the newest by the clock that minted it. Expected to dominate.
    OUT_OF_ORDER_ARRIVAL,

    /// Portal's repair recursion re-`add`s a patch it has already applied, so the same id appears
    /// twice in the `history` array and can re-apply a stale value on the second pass.
    DUPLICATED_HISTORY_ENTRY,

    /// REC-011: stored `Personal`, folded the deployment's name. All 8,086 recurring tasks carry a
    /// hardcoded `Personal`, and the deployment name overwrites it or four contexts collapse into
    /// one card.
    CONTEXT_OVERWRITTEN_BY_DEPLOYMENT,

    /// `Contexts` trimmed the value or folded it onto a canonical spelling —
    /// `Scholencoordinatie` → `Scholencoördinatie`, `Medisch huis` → `Medisch Huis`.
    CONTEXT_NORMALISED,

    /// ADR-0018: portal stored no importance at all and the fold writes `NOT_SO_IMPORTANT`, because
    /// null was undefined rather than unimportant and the case is deleted rather than ruled on.
    IMPORTANCE_DEFAULTED,

    /// A patch cleared `startDateTime` to null, which the new model cannot hold, so the task starts
    /// the day it was created.
    CLEARED_START_DATE_DEFAULTED,

    /// **Fits none of the above.** The report's first page, and the only thing a person has to
    /// adjudicate.
    UNEXPLAINED
}
