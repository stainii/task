package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.mapper.TaskMapperImpl;
import be.stijnhooft.task.backend.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static be.stijnhooft.task.backend.task.mother.TaskMother.createRandomTask;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@Import(TaskMapperImpl.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    @Test
    void snapshot_returnsTheOpenTasksUnderTheCursorTheyWereReadAt() throws Exception {
        var task1 = createRandomTask();
        var task2 = createRandomTask();

        when(taskService.snapshot()).thenReturn(new TaskService.Snapshot(2L, 87L, List.of(task1, task2)));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.watermark").value(87))
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[0].id").value(task1.id().toString()))
                .andExpect(jsonPath("$.tasks[0].name").value(task1.name()))
                .andExpect(jsonPath("$.tasks[0].importance").value(task1.importance().toString()))
                .andExpect(jsonPath("$.tasks[0].description").value(task1.description()))
                .andExpect(jsonPath("$.tasks[0].dueDate").value(task1.dueDate().toString()))
                .andExpect(jsonPath("$.tasks[0].startDate").value(task1.startDate().toString()))
                .andExpect(jsonPath("$.tasks[0].context").value(task1.context()))
                .andExpect(jsonPath("$.tasks[0].history").isNotEmpty())

                .andExpect(jsonPath("$.tasks[1].id").value(task2.id().toString()))
                .andExpect(jsonPath("$.tasks[1].history").isNotEmpty());
    }

    /// The watermark is the point of the snapshot, so an empty one still carries it: a first run on
    /// an empty database must stream from somewhere.
    @Test
    void snapshot_carriesTheCursorEvenWithNoTasks() throws Exception {
        when(taskService.snapshot()).thenReturn(new TaskService.Snapshot(1L, 0L, Collections.emptyList()));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.watermark").value(0))
                .andExpect(jsonPath("$.tasks.length()").value(0));
    }

    /// One write verb (ADR-0004). The whole-task endpoint carried nothing the creation patch does
    /// not, and cost the client a second item shape in its outbox plus a rule about their order -
    /// which is the rule that breaks on a flaky reconnect.
    @Test
    void thereIsNoWayToCreateAWholeTask() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "Renew the insurance",
                                  "context": "Admin"
                                }
                                """))
                .andExpect(status().isMethodNotAllowed());
    }
}
