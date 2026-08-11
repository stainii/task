package be.stijnhooft.task.backend.task;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/// A change to a set of a task's fields, minted by the client that made it.
///
/// Immutable and append-only: a patch is a record of something that happened, so nothing ever
/// edits one. Undo does not rewrite a patch either - it appends another one carrying
/// [#voids], and the fold recomputes ([ADR-0004](../../../../../../../../docs/adr/0004-one-write-verb-two-clocks-offline-sync.md)).
///
/// **Two clocks, and neither may do the other's job.** [#dateTime] is minted by the client and
/// orders the *fold*; [#sequence] is assigned by the server on receipt and orders *delivery*.
/// Folding on `sequence` would let an online Tuesday edit beat an offline Monday one; delivering
/// on `dateTime` never delivers that Monday patch at all, because the reader's cursor has already
/// passed Tuesday.
///
/// Deliberately not Lombok: a hand-written builder cannot hide a `now()` inside a generated
/// `$default$` method, which is the hole #44 found in the Error Prone gate.
public record TaskPatch(

        /// Minted by the client, because an offline device needs an id before any server exists
        /// to ask for one. It is therefore also the idempotency key: a patch id already stored is
        /// a no-op, not an error (ADR-0004, ADR-0010).
        @Id UUID id,

        UUID taskId,

        /// The client's clock. Orders the fold, never delivery.
        Instant dateTime,

        /// The server's clock, assigned on receipt. Orders delivery, never the fold.
        /// Null only between minting a patch and persisting it.
        @Nullable Long sequence,

        /// The id of the patch this one undoes, or null. The fold drops the voided patch's
        /// contribution entirely - it does not compute a compensating value, which is what made
        /// an offline undo clobber another device's later edit.
        @Nullable UUID voids,

        Map<String, String> changes) {

    /// `Collections.unmodifiableMap` over a copy rather than `Map.copyOf`: a change *to null* is
    /// how a field is cleared, and `Map.copyOf` rejects null values.
    public TaskPatch {
        changes = changes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(changes));
    }

    public boolean containsChange(String field) {
        return changes.containsKey(field);
    }

    public @Nullable String getChange(String field) {
        return changes.get(field);
    }

    public TaskPatch withSequence(long sequence) {
        return new TaskPatch(id, taskId, dateTime, sequence, voids, changes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /// Hand-written on purpose - see the note on the record itself.
    public static final class Builder {

        private @Nullable UUID id;
        private @Nullable UUID taskId;
        private @Nullable Instant dateTime;
        private @Nullable Long sequence;
        private @Nullable UUID voids;
        private final Map<String, String> changes = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder taskId(UUID taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder dateTime(Instant dateTime) {
            this.dateTime = dateTime;
            return this;
        }

        public Builder sequence(@Nullable Long sequence) {
            this.sequence = sequence;
            return this;
        }

        public Builder voids(@Nullable UUID voids) {
            this.voids = voids;
            return this;
        }

        public Builder changes(Map<String, String> changes) {
            this.changes.putAll(changes);
            return this;
        }

        public Builder change(String key, @Nullable Object value) {
            this.changes.put(key, value == null ? null : value.toString());
            return this;
        }

        public TaskPatch build() {
            var patchId = id == null ? UUID.randomUUID() : id;
            if (taskId == null) {
                throw new IllegalStateException("A task patch without a task id cannot exist: " + patchId);
            }
            if (dateTime == null) {
                throw new IllegalStateException("A task patch without a date-time cannot be folded: " + patchId);
            }
            return new TaskPatch(patchId, taskId, dateTime, sequence, voids, changes);
        }
    }
}
