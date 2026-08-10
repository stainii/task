package be.stijnhooft.task.backend.task.service;

import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskStatus;
import be.stijnhooft.task.backend.task.exception.TaskAlreadyExistsException;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void findAllActiveTasks() {
        var tasks = List.of(
                Instancio.create(Task.class),
                Instancio.create(Task.class)
        );

        when(taskRepository.findByStatus(TaskStatus.OPEN)).thenReturn(tasks);

        var result = taskService.findAllActiveTasks();

        verify(taskRepository).findByStatus(TaskStatus.OPEN);
        assertThat(result).isEqualTo(tasks);
    }

    @Test
    void create_whenSuccess() {
        var task = Task.builderForInitialTask(clock).build();

        when(taskRepository.existsById(task.getId())).thenReturn(false);
        when(taskRepository.save(task)).thenReturn(task);

        var result = taskService.create(task);

        verify(taskRepository).existsById(task.getId());
        verify(taskRepository).save(task);
        verify(taskPatchSseEmitterService).emitNewlyCreatedTaskPatch(task.getCreationPatch());
        assertThat(result).isEqualTo(task);
    }

    @Test
    void create_whenTaskAlreadyExists() {
        var taskToSave = Instancio.create(Task.class);

        when(taskRepository.existsById(taskToSave.getId())).thenReturn(true);

        assertThrows(TaskAlreadyExistsException.class, () -> taskService.create(taskToSave));

        verify(taskRepository).existsById(taskToSave.getId());
    }

    @Test
    void create_whenList() {
        var task1 = Task.builderForInitialTask(clock).build();
        var task2 = Task.builderForInitialTask(clock).build();

        when(taskRepository.existsById(task1.getId())).thenReturn(false);
        when(taskRepository.existsById(task2.getId())).thenReturn(false);
        when(taskRepository.save(task1)).thenReturn(task1);
        when(taskRepository.save(task2)).thenReturn(task2);

        var result = taskService.create(List.of(task1, task2));

        verify(taskRepository).existsById(task1.getId());
        verify(taskRepository).save(task1);
        verify(taskRepository).existsById(task2.getId());
        verify(taskRepository).save(task2);
        assertThat(result).containsExactly(task1, task2);
    }

}
