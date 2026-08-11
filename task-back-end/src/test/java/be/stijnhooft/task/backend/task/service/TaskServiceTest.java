package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskStatus;
import be.stijnhooft.task.backend.task.exception.TaskAlreadyExistsException;
import be.stijnhooft.task.backend.task.repository.TaskPatchSequence;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static be.stijnhooft.task.backend.task.mother.TaskMother.createRandomTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private final TestClock clock = TestClock.atNoonOn(LocalDate.of(2026, 8, 10));

    @InjectMocks
    private TaskService taskService;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskPatchSseEmitterService taskPatchSseEmitterService;

    @Mock
    private TaskPatchSequence taskPatchSequence;

    @Test
    void findAllActiveTasks() {
        var tasks = List.of(createRandomTask(), createRandomTask());

        when(taskRepository.findByStatus(TaskStatus.OPEN)).thenReturn(tasks);

        var result = taskService.findAllActiveTasks();

        verify(taskRepository).findByStatus(TaskStatus.OPEN);
        assertThat(result).isEqualTo(tasks);
    }

    @Test
    void create_stampsTheCreationPatchWithASequence() {
        var task = Task.builderForInitialTask(clock).name("n").context("c").build();

        when(taskRepository.existsById(task.id())).thenReturn(false);
        when(taskPatchSequence.next()).thenReturn(12L);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = taskService.create(task);

        var saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getCreationPatch().sequence()).isEqualTo(12L);
        verify(taskPatchSseEmitterService).emitNewlyCreatedTaskPatch(result.getCreationPatch());
        assertThat(result.getCreationPatch().sequence()).isEqualTo(12L);
    }

    @Test
    void create_whenTaskAlreadyExists() {
        var taskToSave = createRandomTask();

        when(taskRepository.existsById(taskToSave.id())).thenReturn(true);

        assertThrows(TaskAlreadyExistsException.class, () -> taskService.create(taskToSave));

        verify(taskRepository).existsById(taskToSave.id());
    }

    @Test
    void create_whenList() {
        var task1 = Task.builderForInitialTask(clock).name("one").context("c").build();
        var task2 = Task.builderForInitialTask(clock).name("two").context("c").build();

        when(taskRepository.existsById(task1.id())).thenReturn(false);
        when(taskRepository.existsById(task2.id())).thenReturn(false);
        when(taskPatchSequence.next()).thenReturn(1L, 2L);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = taskService.create(List.of(task1, task2));

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().name()).isEqualTo("one");
        assertThat(result.getLast().name()).isEqualTo("two");
        assertThat(result.getFirst().getCreationPatch().sequence()).isEqualTo(1L);
        assertThat(result.getLast().getCreationPatch().sequence()).isEqualTo(2L);
    }
}
