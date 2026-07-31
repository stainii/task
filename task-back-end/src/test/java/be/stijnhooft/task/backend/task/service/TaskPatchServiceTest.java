package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.exception.TaskNotFoundException;
import be.stijnhooft.task.backend.task.repository.TaskPatchRepository;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.apache.commons.lang3.NotImplementedException;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.junit.jupiter.api.Assertions.*;
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

    @Test
    void findById_success() {
        var id = UUID.randomUUID();
        var taskPatch = Instancio.create(TaskPatch.class);
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
    void patch_success() {
        var task = Instancio.create(Task.class);
        var taskPatch = Instancio.of(TaskPatch.class)
                .set(field(TaskPatch::getTaskId), task.getId())
                .create();
        var spiedTask = spy(task);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(spiedTask));

        taskPatchService.patch(taskPatch);

        verify(spiedTask).patch(taskPatch);
        verify(taskRepository).save(spiedTask);
        verify(taskPatchSseEmitterService).emitNewlyCreatedTaskPatch(taskPatch);
    }

    @Test
    void patch_notFound() {
        var taskPatch = Instancio.of(TaskPatch.class)
                .create();
        when(taskRepository.findById(taskPatch.getTaskId())).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> taskPatchService.patch(taskPatch));

        verify(taskRepository, never()).save(any(Task.class));
        verify(taskPatchSseEmitterService, never()).emitNewlyCreatedTaskPatch(taskPatch);
    }

    @Test
    void undoPatch_success() {
        var task = Instancio.create(Task.class);
        var taskPatch = Instancio.of(TaskPatch.class)
                .set(field(TaskPatch::getTaskId), task.getId())
                .create();
        var spiedTask = spy(task);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(spiedTask));
        taskPatchService.patch(taskPatch);

        taskPatchService.undoPatch(taskPatch);

        verify(spiedTask).undoPatch(taskPatch);
        verify(taskRepository, times(2)).save(spiedTask);
        verify(taskPatchSseEmitterService, times(2)).emitNewlyCreatedTaskPatch(taskPatch);
    }

    @Test
    void undoPatch_whenPatchIsNotInHistory() {
        var task = Instancio.create(Task.class);
        var taskPatch = Instancio.of(TaskPatch.class)
                .set(field(TaskPatch::getTaskId), task.getId())
                .create();
        var spiedTask = spy(task);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(spiedTask));

        assertThrows(IllegalArgumentException.class, () -> taskPatchService.undoPatch(taskPatch));

        verify(spiedTask).undoPatch(taskPatch);
        verify(taskRepository, never()).save(spiedTask);
        verify(taskPatchSseEmitterService, never()).emitNewlyCreatedTaskPatch(taskPatch);
    }

    @Test
    void undoPatch_notFound() {
        var taskPatch = Instancio.of(TaskPatch.class)
                .create();
        when(taskRepository.findById(taskPatch.getTaskId())).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> taskPatchService.undoPatch(taskPatch));

        verify(taskRepository, never()).save(any(Task.class));
        verify(taskPatchSseEmitterService, never()).emitNewlyCreatedTaskPatch(taskPatch);
    }

    @Test
    void tail_whenProvidingSinceParameter_thanTaskPatchesSinceThatDateAreEmitted() {
        var since = LocalDateTime.now().minusYears(1);
        var patchesSince = Instancio.ofList(TaskPatch.class)
                .size(3)
                .create();
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
        var actualSseEmitter = taskPatchService.tail(null);

        assertThat(actualSseEmitter).isSameAs(expectedSseEmitter);
        verify(taskPatchSseEmitterService).createListener();
        verify(taskPatchSseEmitterService, never()).emitEarlierCreatedTaskPatches(eq(actualSseEmitter), any());
    }
}
