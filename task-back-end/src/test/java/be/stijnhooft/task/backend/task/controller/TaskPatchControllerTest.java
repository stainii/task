package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.dto.TaskPatchDto;
import be.stijnhooft.task.backend.task.exception.InvalidPatchException;
import be.stijnhooft.task.backend.task.exception.OrphanPatchException;
import be.stijnhooft.task.backend.task.exception.PatchTooLargeException;
import be.stijnhooft.task.backend.task.mapper.TaskPatchMapper;
import be.stijnhooft.task.backend.task.service.TaskPatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
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
@Import(TaskApiExceptionHandler.class)
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
        var taskPatchDto = new TaskPatchDto(
                UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-03-01T00:00:00Z"), null, voided, Map.of());
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(taskPatchDto.id(), taskPatchDto.taskId(), voided)))
                .andExpect(status().isOk());

        verify(taskPatchService).patch(taskPatch);
    }

    /// A retry after a lost response is the ordinary case, not an error: the client-minted id is the
    /// idempotency key. A `500` here would stall that device's outbox permanently (ADR-0010).
    @Test
    void patch_whenTheSamePatchIsSentTwice_thenBothAre200() throws Exception {
        var taskPatchDto = createRandomTaskPatchDto(Map.of("name", "value1"));
        var taskPatch = createRandomTaskPatch();
        var body = body(taskPatchDto.id(), taskPatchDto.taskId(), null);

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);

        mockMvc.perform(post("/api/task-patches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/task-patches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(taskPatchService, times(2)).patch(taskPatch);
    }

    @Test
    void patch_404() throws Exception {
        var taskPatchDto = createRandomTaskPatchDto(Map.of("name", "value1"));
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);
        doThrow(new OrphanPatchException(taskPatchDto.taskId())).when(taskPatchService).patch(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(taskPatchDto.id(), taskPatchDto.taskId(), null)))
                .andExpect(status().isNotFound());

        verify(taskPatchService).patch(taskPatch);
    }

    @Test
    void patch_400() throws Exception {
        var taskPatchDto = createRandomTaskPatchDto(Map.of("name", "value1"));
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);
        doThrow(new InvalidPatchException("'colour' names no field of a task."))
                .when(taskPatchService).patch(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(taskPatchDto.id(), taskPatchDto.taskId(), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_413() throws Exception {
        var taskPatchDto = createRandomTaskPatchDto(Map.of("name", "value1"));
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);
        doThrow(new PatchTooLargeException(70_000, 65_536)).when(taskPatchService).patch(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(taskPatchDto.id(), taskPatchDto.taskId(), null)))
                .andExpect(status().isPayloadTooLarge());
    }

    /// **D5.** A body with no `changes` used to reach the mapper and blow up as a `500`, which the
    /// client's outbox reads as *the server is down* and retries forever, freezing every write
    /// behind it.
    @Test
    void patch_whenTheBodyHasNoChangesMap_then400() throws Exception {
        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "%s",
                                  "taskId": "%s",
                                  "dateTime": "2026-03-01T00:00:00Z"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskPatchService);
    }

    @Test
    void patch_whenTheBodyIsNotAPatchAtAll_then400() throws Exception {
        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"dateTime\": \"whenever\" }"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskPatchService);
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
