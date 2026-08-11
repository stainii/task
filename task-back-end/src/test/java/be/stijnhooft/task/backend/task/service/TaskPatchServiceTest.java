package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.exception.TaskNotFoundException;
import be.stijnhooft.task.backend.task.repository.TaskPatchRepository;
import be.stijnhooft.task.backend.task.repository.TaskPatchSequence;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static be.stijnhooft.task.backend.task.mother.TaskMother.createRandomTask;
import static be.stijnhooft.task.backend.task.mother.TaskPatchMother.createRandomTaskPatch;
import static be.stijnhooft.task.backend.task.mother.TaskPatchMother.taskPatchAt;
import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void patch_notFound() {
        var taskPatch = createRandomTaskPatch();
        when(taskRepository.findById(taskPatch.taskId())).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> taskPatchService.patch(taskPatch));

        verify(taskRepository, never()).save(any(Task.class));
        verify(taskPatchSseEmitterService, never()).emitNewlyCreatedTaskPatch(taskPatch);
    }

    @Test
    void tail_whenProvidingSinceParameter_thanTaskPatchesSinceThatDateAreEmitted() {
        var since = LocalDate.of(2025, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var patchesSince = List.of(createRandomTaskPatch(), createRandomTaskPatch());
        var expectedSseEmitter = new SseEmitter();

        when(taskPatchSseEmitterService.createListener()).thenReturn(expectedSseEmitter);
        when(taskPatchRepository.findByDateTimeAfter(since)).thenReturn(patchesSince);
        var actualSseEmitter = taskPatchService.tail(since);

        assertThat(actualSseEmitter).isSameAs(expectedSseEmitter);
        verify(taskPatchSseEmitterService).createListener();
        verify(taskPatchSseEmitterService).emitEarlierCreatedTaskPatches(actualSseEmitter, patchesSince);
    }

    @Test
    void tail_whenNotProvidingSinceParameter_thenNoEarlierCreatedTaskPatchesAreEmitted() {
        var expectedSseEmitter = new SseEmitter();
        when(taskPatchSseEmitterService.createListener()).thenReturn(expectedSseEmitter);

        var actualSseEmitter = taskPatchService.tail((Instant) null);

        assertThat(actualSseEmitter).isSameAs(expectedSseEmitter);
        verify(taskPatchSseEmitterService).createListener();
        verify(taskPatchSseEmitterService, never()).emitEarlierCreatedTaskPatches(eq(actualSseEmitter), any());
    }
}
