package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.domain.Task;
import be.stijnhooft.task.backend.task.domain.TaskPatch;
import be.stijnhooft.task.backend.task.exception.CursorWithoutEpochException;
import be.stijnhooft.task.backend.task.exception.InvalidPatchException;
import be.stijnhooft.task.backend.task.exception.OrphanPatchException;
import be.stijnhooft.task.backend.task.exception.PatchTooLargeException;
import be.stijnhooft.task.backend.task.repository.SyncEpoch;
import be.stijnhooft.task.backend.task.repository.TaskPatchRepository;
import be.stijnhooft.task.backend.task.repository.TaskPatchSequence;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static be.stijnhooft.task.backend.task.mother.TaskMother.createRandomTask;
import static be.stijnhooft.task.backend.task.mother.TaskPatchMother.createRandomTaskPatch;
import static be.stijnhooft.task.backend.task.mother.TaskPatchMother.taskPatchAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskPatchServiceTest {

    @InjectMocks
    private TaskPatchService taskPatchService;

    @Mock
    private TaskPatchRepository taskPatchRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskPatchSseEmitterService taskPatchSseEmitterService;

    @Mock
    private TaskPatchSequence taskPatchSequence;

    @Mock
    private SyncEpoch syncEpoch;

    @Mock
    private TransactionTemplate transactionTemplate;

    /// The write runs inside a transaction template rather than under `@Transactional`, because the
    /// retry has to sit *outside* the transaction it is retrying. Here the template just runs its
    /// callback.
    @BeforeEach
    void runTheTransactionCallback() {
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation.getArgument(0, TransactionCallback.class)
                        .doInTransaction(null));
    }

    @Test
    void findById_success() {
        var id = UUID.randomUUID();
        var taskPatch = createRandomTaskPatch();
        when(taskPatchRepository.findById(id)).thenReturn(Optional.of(taskPatch));

        var result = taskPatchService.findById(id);

        verify(taskPatchRepository).findById(id);
        assertThat(result).isEqualTo(Optional.of(taskPatch));
    }

    @Test
    void findById_notFound() {
        var id = UUID.randomUUID();
        when(taskPatchRepository.findById(id)).thenReturn(Optional.empty());

        var result = taskPatchService.findById(id);

        verify(taskPatchRepository).findById(id);
        assertThat(result).isEqualTo(Optional.empty());
    }

    @Test
    void patch_stampsASequenceAndSavesTheRefoldedTask() {
        var task = createRandomTask();
        var taskPatch = taskPatchAt(task.id(), task.creationDateTime().plusSeconds(3600), "name", "patched");
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskPatchSequence.next()).thenReturn(77L);

        taskPatchService.patch(taskPatch);

        var saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().history())
                .filteredOn(patch -> patch.id().equals(taskPatch.id()))
                .singleElement()
                .satisfies(patch -> assertThat(patch.sequence()).isEqualTo(77L));

        verify(taskPatchSseEmitterService).emitNewlyCreatedTaskPatch(taskPatch.withSequence(77L));
    }

    /// A retry after a lost response must change nothing at all - not the task, and not the
    /// sequence every client reads by. Burning a number would leave a gap that looks like a patch
    /// the client failed to receive.
    @Test
    void patch_whenThePatchIsAlreadyInTheHistory_thenNothingHappensAndNoSequenceIsBurned() {
        var task = createRandomTask();
        var alreadyStored = task.history().getLast();

        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));

        taskPatchService.patch(alreadyStored);

        verify(taskRepository, never()).save(any(Task.class));
        verify(taskPatchSequence, never()).next();
        verify(taskPatchSseEmitterService, never()).emitNewlyCreatedTaskPatch(any());
    }

    /// The first patch for a task id creates it. No `create` flag on the wire: a creating patch is
    /// the one carrying `creationDateTime`, which only a full field dump ever does.
    @Test
    void patch_whenTheTaskDoesNotExistAndThePatchCreatesIt_thenTheTaskIsCreated() {
        var creationPatch = Task.builderForInitialTask(TestClock.atNoonOn(LocalDate.of(2026, 3, 1)))
                .name("a task nobody has told the server about yet")
                .context("Personal")
                .build()
                .getCreationPatch();
        when(taskRepository.findById(creationPatch.taskId())).thenReturn(Optional.empty());
        when(taskPatchSequence.next()).thenReturn(5L);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        taskPatchService.patch(creationPatch);

        var saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(creationPatch.taskId());
        assertThat(saved.getValue().history()).singleElement()
                .satisfies(patch -> assertThat(patch.sequence()).isEqualTo(5L));
    }

    /// An orphan: a patch naming a task nobody has ever heard of, that does not create it either.
    /// A `404`, so the client's outbox drops it and keeps draining rather than stalling on it.
    @Test
    void patch_whenTheTaskDoesNotExistAndThePatchDoesNotCreateIt_then404() {
        var taskPatch = createRandomTaskPatch();
        when(taskRepository.findById(taskPatch.taskId())).thenReturn(Optional.empty());

        assertThrows(OrphanPatchException.class, () -> taskPatchService.patch(taskPatch));

        verify(taskRepository, never()).save(any(Task.class));
        verify(taskPatchSseEmitterService, never()).emitNewlyCreatedTaskPatch(taskPatch);
    }

    /// A creating patch that does not carry every field is a `400`, not a `404`: it is the client's
    /// own work that is about to be dropped, so it has to be visible rather than swallowed.
    @Test
    void patch_whenACreatingPatchIsIncomplete_then400() {
        var taskId = UUID.randomUUID();
        var incomplete = TaskPatch.builder()
                .taskId(taskId)
                .dateTime(Instant.parse("2026-03-01T00:00:00Z"))
                .change("creationDateTime", "2026-03-01T00:00:00Z")
                .build();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskPatchService.patch(incomplete))
                .isInstanceOf(InvalidPatchException.class);

        verify(taskRepository, never()).save(any(Task.class));
    }

    /// The API refuses a change key the fold would silently ignore. The log is append-only and
    /// ADR-0005 replays it forever, so anything accepted is accepted permanently.
    @Test
    void patch_whenAChangeNamesNoField_then400() {
        var taskPatch = taskPatchAt(UUID.randomUUID(), Instant.parse("2026-03-01T00:00:00Z"), "colour", "blue");

        assertThatThrownBy(() -> taskPatchService.patch(taskPatch))
                .isInstanceOf(InvalidPatchException.class)
                .hasMessageContaining("colour");

        verify(taskRepository, never()).findById(any());
    }

    @Test
    void patch_whenAValueDoesNotParseAsItsField_then400() {
        var taskPatch = taskPatchAt(UUID.randomUUID(), Instant.parse("2026-03-01T00:00:00Z"), "dueDate", "next tuesday");

        assertThatThrownBy(() -> taskPatchService.patch(taskPatch))
                .isInstanceOf(InvalidPatchException.class);

        verify(taskRepository, never()).findById(any());
    }

    @Test
    void patch_whenThePatchChangesNothingAndVoidsNothing_then400() {
        var taskPatch = TaskPatch.builder()
                .taskId(UUID.randomUUID())
                .dateTime(Instant.parse("2026-03-01T00:00:00Z"))
                .build();

        assertThatThrownBy(() -> taskPatchService.patch(taskPatch))
                .isInstanceOf(InvalidPatchException.class);
    }

    @Test
    void patch_whenThePatchIsOverTheSizeCap_then413() {
        var taskPatch = taskPatchAt(UUID.randomUUID(), Instant.parse("2026-03-01T00:00:00Z"),
                "description", "x".repeat(64 * 1024 + 1));

        assertThatThrownBy(() -> taskPatchService.patch(taskPatch))
                .isInstanceOf(PatchTooLargeException.class);
    }

    /// `409` never reaches the client (ADR-0004): the loser of a write race re-reads and folds onto
    /// the winner's task, which is the right answer rather than a papered-over one.
    @Test
    void patch_whenItLosesAWriteRace_thenItIsRetriedAgainstTheWinnersTask() {
        var task = createRandomTask();
        var taskPatch = taskPatchAt(task.id(), task.creationDateTime().plusSeconds(3600), "name", "patched");
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskPatchSequence.next()).thenReturn(77L);
        // doThrow/doAnswer, not when(...): re-stubbing with `when(mock.execute(any()))` *calls* the
        // mock with a null argument, which runs the answer set up above and dies inside it.
        doThrow(new OptimisticLockingFailureException("another device got there first"))
                .doAnswer(invocation -> invocation.getArgument(0, TransactionCallback.class)
                        .doInTransaction(null))
                .when(transactionTemplate).execute(any());

        taskPatchService.patch(taskPatch);

        verify(transactionTemplate, times(2)).execute(any());
        verify(taskPatchSseEmitterService).emitNewlyCreatedTaskPatch(taskPatch.withSequence(77L));
    }

    /// Retrying forever is what the client's `5xx` stall does; the server must not do it too.
    @Test
    void patch_whenItKeepsLosingTheRace_thenItGivesUpRatherThanSpinning() {
        var task = createRandomTask();
        var taskPatch = taskPatchAt(task.id(), task.creationDateTime().plusSeconds(3600), "name", "patched");
        doThrow(new OptimisticLockingFailureException("contended"))
                .when(transactionTemplate).execute(any());

        assertThrows(OptimisticLockingFailureException.class, () -> taskPatchService.patch(taskPatch));

        verify(transactionTemplate, times(3)).execute(any());
        verify(taskPatchSseEmitterService, never()).emitNewlyCreatedTaskPatch(any());
    }

    @Test
    void tail_whenAcursorIsPresented_thenEverythingAfterItIsReplayed() {
        var patchesSince = List.of(createRandomTaskPatch(), createRandomTaskPatch());
        var expectedSseEmitter = new SseEmitter();

        when(syncEpoch.refresh()).thenReturn(1L);
        when(taskPatchSequence.watermark()).thenReturn(40L);
        when(taskPatchSseEmitterService.createListener()).thenReturn(expectedSseEmitter);
        when(taskPatchRepository.findBySequenceGreaterThanOrderBySequenceAsc(12L)).thenReturn(patchesSince);

        var actualSseEmitter = taskPatchService.tail(null, 12L, 1L);

        assertThat(actualSseEmitter).isSameAs(expectedSseEmitter);
        verify(taskPatchSseEmitterService).emitEarlierCreatedTaskPatches(actualSseEmitter, patchesSince);
        verify(taskPatchSseEmitterService, never()).emitResync(any());
    }

    /// `Last-Event-ID` is the browser's own reconnect and wins over a parameter the application
    /// supplied, because it is the fresher of the two.
    @Test
    void tail_whenBothCursorsArePresented_thenTheHeaderWins() {
        var expectedSseEmitter = new SseEmitter();
        when(syncEpoch.refresh()).thenReturn(1L);
        when(taskPatchSequence.watermark()).thenReturn(40L);
        when(taskPatchSseEmitterService.createListener()).thenReturn(expectedSseEmitter);
        when(taskPatchRepository.findBySequenceGreaterThanOrderBySequenceAsc(anyLong())).thenReturn(List.of());

        taskPatchService.tail("1:30", 12L, 1L);

        verify(taskPatchRepository).findBySequenceGreaterThanOrderBySequenceAsc(30L);
    }

    @Test
    void tail_whenNoCursorIsPresented_thenNothingIsReplayed() {
        var expectedSseEmitter = new SseEmitter();
        when(syncEpoch.refresh()).thenReturn(1L);
        when(taskPatchSseEmitterService.createListener()).thenReturn(expectedSseEmitter);

        var actualSseEmitter = taskPatchService.tail(null, null, null);

        assertThat(actualSseEmitter).isSameAs(expectedSseEmitter);
        verify(taskPatchSseEmitterService, never()).emitEarlierCreatedTaskPatches(any(), any());
        verify(taskPatchSseEmitterService, never()).emitResync(any());
    }

    /// The restore case: a backup rewinds the counter, the server reissues numbers it has already
    /// handed out, and a client that was ahead would otherwise conclude it is up to date forever.
    @Test
    void tail_whenTheCursorIsFromAnEarlierEpoch_thenTheClientIsResynced() {
        var expectedSseEmitter = new SseEmitter();
        when(syncEpoch.refresh()).thenReturn(2L);
        when(taskPatchSseEmitterService.createListener()).thenReturn(expectedSseEmitter);

        taskPatchService.tail("1:30", null, null);

        verify(taskPatchSseEmitterService).emitResync(expectedSseEmitter);
        verify(taskPatchSseEmitterService, never()).emitEarlierCreatedTaskPatches(any(), any());
    }

    @Test
    void tail_whenTheCursorIsPastTheEndOfHistory_thenTheClientIsResynced() {
        var expectedSseEmitter = new SseEmitter();
        when(syncEpoch.refresh()).thenReturn(1L);
        when(taskPatchSequence.watermark()).thenReturn(40L);
        when(taskPatchSseEmitterService.createListener()).thenReturn(expectedSseEmitter);

        taskPatchService.tail("1:41", null, null);

        verify(taskPatchSseEmitterService).emitResync(expectedSseEmitter);
    }

    /// A `Last-Event-ID` this server did not write names nothing servable. Serving a live tail
    /// instead would look healthy while silently skipping whatever the cursor could not name.
    @Test
    void tail_whenTheEventIdCannotBeRead_thenTheClientIsResynced() {
        var expectedSseEmitter = new SseEmitter();
        when(syncEpoch.refresh()).thenReturn(1L);
        when(taskPatchSseEmitterService.createListener()).thenReturn(expectedSseEmitter);

        taskPatchService.tail("not-a-cursor", null, null);

        verify(taskPatchSseEmitterService).emitResync(expectedSseEmitter);
    }

    /// A cursor whose lineage is assumed to be the current one can never be found stale, which
    /// turns the epoch off for exactly the client that needed it.
    @Test
    void tail_whenSinceIsPresentedWithoutItsEpoch_then400() {
        when(syncEpoch.refresh()).thenReturn(1L);
        when(taskPatchSseEmitterService.createListener()).thenReturn(new SseEmitter());

        assertThrows(CursorWithoutEpochException.class, () -> taskPatchService.tail(null, 12L, null));
    }
}
