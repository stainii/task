package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.Importance;
import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.exception.TaskAlreadyExistsException;
import be.stijnhooft.task.backend.task.mapper.TaskMapperImpl;
import be.stijnhooft.task.backend.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static be.stijnhooft.task.backend.task.mother.TaskMother.createRandomTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@Import(TaskMapperImpl.class)
class TaskControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

    /// The slice gets a clock standing still, so "the controller dates an undated task today"
    /// can be asserted as a date rather than as "not null" (#44).
    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return TestClock.atNoonOn(TODAY);
        }

    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    @Captor
    private ArgumentCaptor<Task> taskCaptor;

    @Test
    void findAllActiveTasks_returnsTasks_whenTasksExist() throws Exception {
        var task1 = createRandomTask();
        var task2 = createRandomTask();
        List<Task> tasks = List.of(task1, task2);

        when(taskService.findAllActiveTasks()).thenReturn(tasks);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(task1.id().toString()))
                .andExpect(jsonPath("$[0].name").value(task1.name()))
                .andExpect(jsonPath("$[0].importance").value(task1.importance().toString()))
                .andExpect(jsonPath("$[0].description").value(task1.description()))
                .andExpect(jsonPath("$[0].dueDate").value(task1.dueDate().toString()))
                .andExpect(jsonPath("$[0].startDate").value(task1.startDate().toString()))
                .andExpect(jsonPath("$[0].context").value(task1.context()))
                .andExpect(jsonPath("$[0].history").isNotEmpty())

                .andExpect(jsonPath("$[1].id").value(task2.id().toString()))
                .andExpect(jsonPath("$[1].name").value(task2.name()))
                .andExpect(jsonPath("$[1].context").value(task2.context()))
                .andExpect(jsonPath("$[1].history").isNotEmpty());
    }

    @Test
    void findAllActiveTasks_returnsEmptyList_whenNoTasks() throws Exception {
        when(taskService.findAllActiveTasks()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void createTask_whenAllFieldsHaveBeenProvided_theyAreUsed() throws Exception {
        when(taskService.create(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "Renew the insurance",
                                  "importance": "VERY_IMPORTANT",
                                  "description": "before the 20th",
                                  "dueDate": "2026-08-20",
                                  "startDate": "2026-08-12",
                                  "creationDateTime": "2026-08-09T07:15:00Z",
                                  "context": "Admin"
                                }
                                """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Renew the insurance"))
                .andExpect(jsonPath("$.importance").value("VERY_IMPORTANT"))
                .andExpect(jsonPath("$.creationDateTime").value("2026-08-09T07:15:00Z"))
                .andExpect(jsonPath("$.history").isNotEmpty());

        verify(taskService).create(taskCaptor.capture());

        var capturedTask = taskCaptor.getValue();
        assertThat(capturedTask.name()).isEqualTo("Renew the insurance");
        assertThat(capturedTask.importance()).isEqualTo(Importance.VERY_IMPORTANT);
        assertThat(capturedTask.description()).isEqualTo("before the 20th");
        assertThat(capturedTask.dueDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(capturedTask.startDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(capturedTask.context()).isEqualTo("Admin");
        assertThat(capturedTask.history()).hasSize(1);
    }

    @Test
    void createTask_whenOnlyRequiredFieldsAreProvided_defaultFieldsGetsFilledIn_othersStayNull() throws Exception {
        when(taskService.create(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "Ask about the bike",
                                  "context": "Personal"
                                }
                                """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.dueDate").isEmpty())
                .andExpect(jsonPath("$.completedOn").isEmpty())
                .andExpect(jsonPath("$.startDate").isNotEmpty())
                .andExpect(jsonPath("$.history").isNotEmpty());

        verify(taskService).create(taskCaptor.capture());

        var capturedTask = taskCaptor.getValue();
        assertThat(capturedTask.description()).isNull();
        assertThat(capturedTask.dueDate()).isNull();
        assertThat(capturedTask.completedOn()).isNull();
        assertThat(capturedTask.startDate()).isEqualTo(TODAY);
        // ADR-0018: a task nobody gave an importance is IMPORTANT, not null. It is what stops a
        // task captured in one keystroke from sinking down the ranking.
        assertThat(capturedTask.importance()).isEqualTo(Importance.IMPORTANT);
        assertThat(capturedTask.history()).hasSize(1);
    }

    @Test
    void createTask_whenTaskAlreadyExists_return409() throws Exception {
        var task = createRandomTask();

        when(taskService.create(any(Task.class))).thenThrow(new TaskAlreadyExistsException(task.id()));

        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "whatever",
                                  "context": "Personal"
                                }
                                """)
                )
                .andExpect(status().isConflict());
    }

}
