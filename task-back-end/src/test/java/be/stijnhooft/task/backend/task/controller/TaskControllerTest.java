package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.TestClock;
import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.exception.TaskAlreadyExistsException;
import be.stijnhooft.task.backend.task.mapper.TaskMapper;
import be.stijnhooft.task.backend.task.mapper.TaskMapperImpl;
import be.stijnhooft.task.backend.task.service.TaskService;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

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
        var task1 = Instancio.create(Task.class);
        var task2 = Instancio.create(Task.class);
        List<Task> tasks = List.of(task1, task2);

        when(taskService.findAllActiveTasks()).thenReturn(tasks);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(task1.getId().toString()))
                .andExpect(jsonPath("$[0].name").value(task1.getName()))
                .andExpect(jsonPath("$[0].importance").value(task1.getImportance().toString()))
                .andExpect(jsonPath("$[0].description").value(task1.getDescription()))
                .andExpect(jsonPath("$[0].dueDate").value(task1.getDueDate().format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .andExpect(jsonPath("$[0].startDate").value(task1.getStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .andExpect(jsonPath("$[0].creationDateTime").value(task1.getCreationDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                .andExpect(jsonPath("$[0].context").value(task1.getContext()))
                .andExpect(jsonPath("$[0].history").isNotEmpty())

                .andExpect(jsonPath("$[1].id").value(task2.getId().toString()))
                .andExpect(jsonPath("$[1].name").value(task2.getName()))
                .andExpect(jsonPath("$[1].importance").value(task2.getImportance().toString()))
                .andExpect(jsonPath("$[1].description").value(task2.getDescription()))
                .andExpect(jsonPath("$[1].dueDate").value(task2.getDueDate().format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .andExpect(jsonPath("$[1].startDate").value(task2.getStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .andExpect(jsonPath("$[1].creationDateTime").value(task2.getCreationDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                .andExpect(jsonPath("$[1].context").value(task2.getContext()))
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
        var task = Instancio.create(Task.class);

        when(taskService.create(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "%s",
                                  "importance": "%s",
                                  "description": "%s",
                                  "dueDate": "%s",
                                  "startDate": "%s",
                                  "creationDateTime": "%s",
                                  "context": "%s",
                                  "history": [
                                      {
                                          "id": "123e4567-e89b-12d3-a456-426614174000",
                                          "taskId": "%s",
                                          "dateTime": "%s",
                                          "changes": {
                                                "anything": "this should be ignored"
                                          }
                                      }
                                  ]
                                }
                                """.formatted(
                                task.getName(),
                                task.getImportance(),
                                task.getDescription(),
                                task.getDueDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                                task.getStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                                task.getCreationDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                task.getContext(),
                                task.getId(),
                                task.getCreationDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        ))
                )
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value(task.getName()))
                .andExpect(jsonPath("$.importance").value(task.getImportance().toString()))
                .andExpect(jsonPath("$.description").value(task.getDescription()))
                .andExpect(jsonPath("$.dueDate").value(task.getDueDate().format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .andExpect(jsonPath("$.startDate").value(task.getStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .andExpect(jsonPath("$.creationDateTime").value(task.getCreationDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                .andExpect(jsonPath("$.context").value(task.getContext()))
                .andExpect(jsonPath("$.history").isNotEmpty());

        verify(taskService).create(taskCaptor.capture());

        var capturedTask = taskCaptor.getValue();
        assertThat(capturedTask.getId()).isNotNull();
        assertThat(capturedTask.getName()).isEqualTo(task.getName());
        assertThat(capturedTask.getImportance()).isEqualTo(task.getImportance());
        assertThat(capturedTask.getDescription()).isEqualTo(task.getDescription());
        assertThat(capturedTask.getDueDate()).isEqualTo(task.getDueDate());
        assertThat(capturedTask.getStartDate()).isEqualTo(task.getStartDate());
        assertThat(capturedTask.getCreationDateTime()).isEqualTo(task.getCreationDateTime());
        assertThat(capturedTask.getContext()).isEqualTo(task.getContext());
        assertThat(capturedTask.getHistory()).hasSize(1);
    }

    @Test
    void createTask_whenOnlyRequiredFieldsAreProvided_defaultFieldsGetsFilledIn_othersStayNull() throws Exception {
        var task = Instancio.create(Task.class);
        when(taskService.create(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "%s",
                                  "context": "%s"
                                }
                                """.formatted(
                                task.getName(),
                                task.getContext()
                        ))
                )
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value(task.getName()))
                .andExpect(jsonPath("$.importance").isEmpty())
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.dueDate").isEmpty())
                .andExpect(jsonPath("$.startDate").isNotEmpty())
                .andExpect(jsonPath("$.creationDateTime").isNotEmpty())
                .andExpect(jsonPath("$.context").value(task.getContext()))
                .andExpect(jsonPath("$.history").isNotEmpty());

        verify(taskService).create(taskCaptor.capture());

        var capturedTask = taskCaptor.getValue();
        assertThat(capturedTask.getId()).isNotNull();
        assertThat(capturedTask.getName()).isEqualTo(task.getName());
        assertThat(capturedTask.getImportance()).isNull();
        assertThat(capturedTask.getDescription()).isNull();
        assertThat(capturedTask.getDueDate()).isNull();
        assertThat(capturedTask.getStartDate()).isEqualTo(TODAY);
        assertThat(capturedTask.getCreationDateTime().toLocalDate()).isEqualTo(TODAY);
        assertThat(capturedTask.getContext()).isEqualTo(task.getContext());
        assertThat(capturedTask.getHistory()).hasSize(1);
    }

    @Test
    void createTask_whenTaskAlreadyExists_return409() throws Exception {
        var task = Instancio.create(Task.class);

        when(taskService.create(any(Task.class))).thenThrow(new TaskAlreadyExistsException(task.getId()));

        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "%s",
                                  "context": "%s"
                                }
                                """.formatted(
                                task.getName(),
                                task.getContext()
                        ))
                )
                .andExpect(status().isConflict());
    }

}
