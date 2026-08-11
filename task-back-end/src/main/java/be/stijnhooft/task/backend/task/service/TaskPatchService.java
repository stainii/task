package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.SyncCursor;
import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.exception.CursorWithoutEpochException;
import be.stijnhooft.task.backend.task.exception.IncompleteTaskHistoryException;
import be.stijnhooft.task.backend.task.exception.InvalidPatchException;
import be.stijnhooft.task.backend.task.exception.OrphanPatchException;
import be.stijnhooft.task.backend.task.exception.PatchTooLargeException;
import be.stijnhooft.task.backend.task.repository.SyncEpoch;
import be.stijnhooft.task.backend.task.repository.TaskPatchRepository;
import be.stijnhooft.task.backend.task.repository.TaskPatchSequence;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPatchService {

    /// A patch is a handful of short strings plus, at worst, a description. Well clear of anything
    /// the app writes, and small enough that a runaway client cannot append a megabyte to a log
    /// nothing ever compacts.
    private static final int CHANGES_SIZE_CAP = 64 * 1024;

    /// Two devices patching the same task in the same instant lose a race on `Task`'s version. The
    /// loser re-reads and re-folds, and the second attempt starts from the winner's task - so a
    /// third is only needed if a third device joined in the meantime.
    private static final int MAX_WRITE_ATTEMPTS = 3;

    /// A cursor that names no lineage and no place in it, so every test below it fails and the
    /// client is resynced. Used for a `Last-Event-ID` this server did not write.
    private static final SyncCursor UNSERVABLE = new SyncCursor(-1L, -1L);

    private final TaskPatchRepository taskPatchRepository;
    private final TaskRepository taskRepository;
    private final TaskPatchSseEmitterService taskPatchSseEmitterService;
    private final TaskPatchSequence taskPatchSequence;
    private final SyncEpoch syncEpoch;
    private final TransactionTemplate transactionTemplate;

    public Optional<TaskPatch> findById(UUID id) {
        return taskPatchRepository.findById(id);
    }

    /// Accepts a patch: creating its task if this is the first patch for that id, appending and
    /// refolding otherwise.
    ///
    /// **Nothing is emitted until it has committed.** The stream is how a client learns its write
    /// is durable, so a patch announced from inside the transaction that then rolls back is a
    /// promise the server cannot keep - and the client, having seen its own patch echo back, would
    /// have dropped it from the outbox.
    ///
    /// A patch id already in the history is a no-op that **burns no sequence number**: the id is the
    /// idempotency key, so a retry after a lost response changes nothing at all - not the task, and
    /// not the cursor every client reads by (ADR-0010).
    public void patch(TaskPatch taskPatch) {
        validate(taskPatch);
        write(taskPatch).ifPresent(taskPatchSseEmitterService::emitNewlyCreatedTaskPatch);
    }

    /// Validation at the door, which is a different question from what the fold can survive.
    ///
    /// The fold ignores a change key it does not recognise, because it also replays years of
    /// migrated history that never came through here (ADR-0005). The API refuses it, because
    /// anything accepted into an append-only log is accepted permanently.
    private void validate(TaskPatch taskPatch) {
        var size = taskPatch.changes().entrySet().stream()
                .mapToInt(change -> change.getKey().length()
                        + (change.getValue() == null ? 0 : change.getValue().length()))
                .sum();
        if (size > CHANGES_SIZE_CAP) {
            throw new PatchTooLargeException(size, CHANGES_SIZE_CAP);
        }

        if (taskPatch.changes().isEmpty() && taskPatch.voids() == null) {
            throw new InvalidPatchException("Patch " + taskPatch.id()
                    + " changes nothing and voids nothing, so there is nothing for the fold to do.");
        }

        taskPatch.changes().forEach((field, value) -> {
            try {
                Task.requireFoldableChange(field, value);
            } catch (IllegalArgumentException e) {
                throw new InvalidPatchException("Patch " + taskPatch.id() + " is not applicable: "
                        + e.getMessage(), e);
            }
        });
    }

    /// Writes the patch, retrying the whole read-fold-save cycle when it loses a race.
    ///
    /// `409` never reaches the client (ADR-0004), so optimistic locking stays out of the contract
    /// entirely - the loser reads the winner's task and folds onto it, which is the right answer
    /// rather than a papered-over one. A unique-key violation is retried for the same reason: the
    /// only way to hit one here is two attempts to create the same task at once, and on the second
    /// pass the task exists and the patch id is already in its history, so it resolves to the no-op
    /// it always was. Retrying forever is what the client's `5xx` stall does; three attempts and an
    /// honest error is what it must not.
    ///
    /// Returns the stored patch, or empty when the patch was already there.
    private Optional<TaskPatch> write(TaskPatch taskPatch) {
        for (var attempt = 1; ; attempt++) {
            try {
                return Objects.requireNonNull(transactionTemplate.execute(status -> store(taskPatch)));
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                if (attempt == MAX_WRITE_ATTEMPTS) {
                    throw e;
                }
                log.debug("Patch {} lost a write race (attempt {}); re-reading and refolding.",
                        taskPatch.id(), attempt, e);
            }
        }
    }

    private Optional<TaskPatch> store(TaskPatch taskPatch) {
        var existing = taskRepository.findById(taskPatch.taskId());
        if (existing.isEmpty()) {
            return Optional.of(create(taskPatch));
        }

        var task = existing.get();
        if (task.history().stream().anyMatch(stored -> stored.id().equals(taskPatch.id()))) {
            log.debug("Patch {} is already part of task {}; nothing to do.", taskPatch.id(), task.id());
            return Optional.empty();
        }

        var sequenced = taskPatch.withSequence(taskPatchSequence.next());
        taskRepository.save(task.patch(sequenced));
        return Optional.of(sequenced);
    }

    /// The first patch for a task id creates it - no `create` flag on the wire.
    ///
    /// **A creating patch is one that carries `creationDateTime`**, which is the discriminator the
    /// model already has: a creation patch is a dump of every field (TODO-046) while an ordinary
    /// edit names only what changed, and nothing else ever restates when the task came into being.
    /// Without it the two rows of ADR-0004's contract table could not both be reachable - an
    /// incomplete create and an orphan look identical on the wire, and one is a `400` the user must
    /// see while the other is a `404` the outbox drops on its own.
    private TaskPatch create(TaskPatch taskPatch) {
        if (!taskPatch.containsChange("creationDateTime")) {
            throw new OrphanPatchException(taskPatch.taskId());
        }

        Task task;
        try {
            task = Task.foldOf(taskPatch.taskId(), List.of(taskPatch), 0L);
        } catch (IncompleteTaskHistoryException e) {
            throw new InvalidPatchException("Patch " + taskPatch.id()
                    + " creates a task and does not carry every field it needs: " + e.getMessage(), e);
        }

        return taskRepository.save(task.withSequencesFrom(taskPatchSequence::next)).getCreationPatch();
    }

    /// Opens a stream, catching the client up from wherever it left off.
    ///
    /// **The listener is registered before the catch-up is read**, so a patch arriving in between is
    /// delivered twice rather than not at all. A duplicate is a no-op by id; a gap is permanent.
    ///
    /// A cursor the server cannot serve - a previous epoch, a sequence past the end of history, or
    /// an id it cannot read - is answered with a **resync**: the client throws its local state away
    /// and re-snapshots. That is the same lever the user's own hard reset pulls, and the alternative
    /// is a stream that looks healthy while it silently skips whatever the cursor could not name.
    public SseEmitter tail(@Nullable String lastEventId, @Nullable Long since, @Nullable Long epoch) {
        var currentEpoch = syncEpoch.refresh();
        var emitter = taskPatchSseEmitterService.createListener();

        var cursor = cursorOf(lastEventId, since, epoch);
        if (cursor.isEmpty()) {
            return emitter;
        }

        var from = cursor.get();
        if (from == UNSERVABLE || from.epoch() != currentEpoch || from.sequence() > taskPatchSequence.watermark()) {
            log.info("Resyncing a client: its cursor {} cannot be served in epoch {}.", from, currentEpoch);
            taskPatchSseEmitterService.emitResync(emitter);
            return emitter;
        }

        taskPatchSseEmitterService.emitEarlierCreatedTaskPatches(
                emitter, taskPatchRepository.findBySequenceGreaterThanOrderBySequenceAsc(from.sequence()));
        return emitter;
    }

    /// `Last-Event-ID` first, `?since=` second (ADR-0004). They serve different callers: the browser
    /// reconnects on its own and sends the header, while a client booting from local storage
    /// presents the cursor it persisted.
    private Optional<SyncCursor> cursorOf(@Nullable String lastEventId, @Nullable Long since, @Nullable Long epoch) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            var parsed = SyncCursor.parse(lastEventId);
            return Optional.of(parsed == null ? UNSERVABLE : parsed);
        }
        if (since == null) {
            return Optional.empty();
        }
        if (epoch == null) {
            throw new CursorWithoutEpochException();
        }
        return Optional.of(new SyncCursor(epoch, since));
    }
}
