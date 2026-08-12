package be.stijnhooft.task.backend.migration.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// ADR-0005 §241 claimed *"everything else is already a UUID"*. Eleven task ids and 115 patch ids in
/// the archive say otherwise, so these are the values that have to be minted — and minted the same
/// way twice, because the importer's one non-negotiable property is that a dry run is free.
class PortalIdsTest {

    @Test
    void aUuidTaskIdIsItsOwnId() {
        var id = "0004781e-7dbe-4aae-82aa-03c1c3f5283c";

        assertThat(PortalIds.ofTask(id)).isEqualTo(UUID.fromString(id));
    }

    /// Portal's earliest generated tasks, from before the UUID scheme. Their id *is* the flow id,
    /// and one of them is a stray called `Healthy`.
    @ParameterizedTest
    @ValueSource(strings = {"Health-1", "Housagotchi-52", "Healthy"})
    void aTaskIdThatIsNotAUuidIsMinted(String portalId) {
        assertThat(PortalIds.ofTask(portalId)).isNotNull();
        assertThat(PortalIds.ofTask(portalId)).isEqualTo(PortalIds.ofTask(portalId));
    }

    /// The 115 Mongo `ObjectId`s. ADR-0005 predicted the shape and attributed it to undo patches;
    /// they are ordinary edits, but they still need minting.
    @Test
    void anObjectIdPatchIdIsMinted() {
        var objectId = "ObjectId(\"5f36fa758c5f265d1501441c\")";

        assertThat(PortalIds.ofPatch(objectId)).isEqualTo(PortalIds.ofPatch(objectId));
    }

    /// **The property the whole importer rests on.** A random id would make two dry runs disagree
    /// about 126 rows, and the diff that is meant to be the cutover gate would be noise.
    @Test
    void mintingIsDeterministicAcrossRuns() {
        assertThat(PortalIds.ofTask("Health-1")).isEqualTo(PortalIds.ofTask("Health-1"));
        assertThat(PortalIds.ofSynthesisedTask("Setlist", 402L))
                .isEqualTo(PortalIds.ofSynthesisedTask("Setlist", 402L));
        assertThat(PortalIds.ofSynthesisedPatch("Setlist", 402L, "create"))
                .isEqualTo(PortalIds.ofSynthesisedPatch("Setlist", 402L, "create"));
    }

    /// Namespaced, so a task and a patch that shared a portal id could not collide — and the two
    /// patches of one synthesised task cannot either.
    @Test
    void differentKindsOfThingDoNotCollide() {
        assertThat(PortalIds.ofTask("Health-1")).isNotEqualTo(PortalIds.ofPatch("Health-1"));
        assertThat(PortalIds.ofSynthesisedPatch("Setlist", 402L, "create"))
                .isNotEqualTo(PortalIds.ofSynthesisedPatch("Setlist", 402L, "complete"));
    }

    /// The four databases number their rows independently, so `1` names a different chore in each.
    @Test
    void aRecurringTaskIsIdentifiedByItsDeploymentToo() {
        assertThat(PortalIds.ofRecurringTask("Health", "1"))
                .isNotEqualTo(PortalIds.ofRecurringTask("Setlist", "1"));
    }

    /// `UUID.fromString` accepts `1-2-3-4-5`, so trusting it to reject would mint some malformed ids
    /// and parse others — the kind of split behaviour that only shows up in one row.
    @Test
    void aMalformedIdIsMintedRatherThanLenientlyParsed() {
        assertThat(PortalIds.ofTask("1-2-3-4-5")).isEqualTo(PortalIds.ofTask("1-2-3-4-5"));
        assertThat(PortalIds.ofTask("1-2-3-4-5")).isNotEqualTo(UUID.fromString("1-2-3-4-5"));
    }
}
