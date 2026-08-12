package be.stijnhooft.task.backend.migration.map;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/// What a migrated task's `context` becomes — which
/// [ADR-0006](../../../../../../../../docs/adr/0006-one-overview-grouped-by-a-swappable-axis.md)
/// makes the overview's grouping axis, so every value here lands in the UI as a card label. A
/// normalisation choice, not a tidy-up.
///
/// ### Recurring tasks take their deployment's name
///
/// REC-011 turns `deployment-name` into `context`. All **8,086** recurring tasks in the archive
/// carry a `context` of `Personal`, hardcoded by the four subscriptions' `mappingOfContext`, so the
/// field is occupied and the deployment name has to overwrite it. Doing nothing would collapse
/// housagotchi, setlist, health and social into one card and throw away the only thing that told
/// them apart — the `flowId`, which is gone after import.
///
/// ### Everything else is trimmed, and three near-duplicates are folded
///
/// The 3,769 hand-made tasks carry 24 further values, three of which are the same context spelled
/// two ways. Left alone they become two cards each:
///
/// | kept | folded into it |
/// |---|---|
/// | `Personal` | `Personal ` (trailing space, 1 task) |
/// | `Scholencoördinatie` | `Scholencoordinatie` (1,293), `Scholencoordinatie ` (1) |
/// | `Medisch Huis` | `Medisch huis` (7) |
///
/// `Scholencoördinatie` is the minority spelling by a wide margin and is still the one kept: this
/// is a label a person reads, and the accented spelling is the correct Dutch. Every fold is counted
/// and reported, so a choice made here is visible rather than assumed.
public final class Contexts {

    /// Folds applied after trimming, keyed by a comparison form that ignores case and accents so
    /// that only the *intended* spelling has to be written down.
    private static final Map<String, String> FOLDS = Map.of(
            comparable("Scholencoordinatie"), "Scholencoördinatie",
            comparable("Medisch Huis"), "Medisch Huis",
            comparable("Personal"), "Personal");

    private Contexts() {
    }

    /// The context a hand-made task keeps: trimmed, with a known near-duplicate folded onto its
    /// canonical spelling. Anything not in the table is returned as the author wrote it.
    public static String normalise(String context) {
        var trimmed = context.trim();
        if (trimmed.isEmpty()) {
            // Never occurs in the archive - no task has a null or blank context - but a blank would
            // otherwise reach a NOT NULL column as an empty card label.
            return "Unknown";
        }
        return FOLDS.getOrDefault(comparable(trimmed), trimmed);
    }

    /// Case- and accent-insensitive comparison form. `Scholencoördinatie` and `Scholencoordinatie`
    /// differ by one combining diaeresis once decomposed, which is what makes them foldable without
    /// listing every spelling.
    private static String comparable(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
    }
}
