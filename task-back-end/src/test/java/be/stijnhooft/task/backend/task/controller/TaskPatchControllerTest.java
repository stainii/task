package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.exception.TaskNotFoundException;
import be.stijnhooft.task.backend.task.mapper.TaskPatchMapper;
import be.stijnhooft.task.backend.task.service.TaskPatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static be.stijnhooft.task.backend.task.mother.TaskPatchDtoMother.createRandomTaskPatchDto;
import static be.stijnhooft.task.backend.task.mother.TaskPatchMother.createRandomTaskPatch;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskPatchController.class)
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
class TaskPatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskPatchService taskPatchService;

    @MockitoBean
    private TaskPatchMapper taskPatchMapper;

    @Test
    void patch_success() throws Exception {
        var taskPatchDto = createRandomTaskPatchDto(Map.of("name", "value1"));
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(taskPatchDto.id(), taskPatchDto.taskId(), null)))
                .andExpect(status().isOk());

        verify(taskPatchService).patch(taskPatch);
    }

    /// Undo travels the same verb as every other write: a patch naming the patch it voids.
    @Test
    void patch_carriesTheVoidedPatchId() throws Exception {
        var voided = UUID.randomUUID();
        var taskPatchDto = new be.stijnhooft.task.backend.task.dto.TaskPatchDto(
                UUID.randomUUID(), UUID.randomUUID(),
                java.time.Instant.parse("2026-03-01T00:00:00Z"), voided, Map.of());
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(taskPatchDto.id(), taskPatchDto.taskId(), voided)))
                .andExpect(status().isOk());

        verify(taskPatchService).patch(taskPatch);
    }

    @Test
    void patch_404() throws Exception {
        var taskPatchDto = createRandomTaskPatchDto(Map.of("name", "value1"));
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);
        doThrow(new TaskNotFoundException(taskPatchDto.taskId())).when(taskPatchService).patch(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(taskPatchDto.id(), taskPatchDto.taskId(), null)))
                .andExpect(status().isNotFound());

        verify(taskPatchService).patch(taskPatch);
    }

    private static String body(UUID id, UUID taskId, UUID voids) {
        return """
                {
                  "id": "%s",
                  "taskId": "%s",
                  "dateTime": "2026-03-01T00:00:00Z",
                  "voids": %s,
                  "changes": %s
                }
                """.formatted(
                id,
                taskId,
                voids == null ? "null" : "\"" + voids + "\"",
                voids == null ? "{\"name\": \"value1\"}" : "{}");
    }
}
