package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.TaskPatch;
import be.stijnhooft.task.backend.task.exception.TaskNotFoundException;
import be.stijnhooft.task.backend.task.mapper.TaskPatchMapper;
import be.stijnhooft.task.backend.task.service.TaskPatchService;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import static be.stijnhooft.task.backend.task.mother.TaskPatchDtoMother.createRandomTaskPatchDto;
import static be.stijnhooft.task.backend.task.mother.TaskPatchMother.createRandomTaskPatch;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
        var taskPatchDto = createRandomTaskPatchDto(Map.of("item1", "value1"));
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "%s",
                                  "dateTime": "%s",
                                  "changes": {
                                    "%s": "%s"
                                  }
                                }
                                """.formatted(
                                taskPatchDto.taskId(),
                                taskPatchDto.dateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                taskPatchDto.changes().entrySet().stream().findFirst().get().getKey(),
                                taskPatchDto.changes().entrySet().stream().findFirst().get().getValue()
                        ))
                )
                .andExpect(status().isOk());

        verify(taskPatchService).patch(taskPatch);
    }

    @Test
    void patch_404() throws Exception {
        var taskPatchDto = createRandomTaskPatchDto(Map.of("item1", "value1"));
        var taskPatch = createRandomTaskPatch();

        when(taskPatchMapper.toDomain(taskPatchDto)).thenReturn(taskPatch);

        doThrow(new TaskNotFoundException(taskPatchDto.taskId())).when(taskPatchService).patch(taskPatch);

        mockMvc.perform(post("/api/task-patches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "%s",
                                  "dateTime": "%s",
                                  "changes": {
                                    "%s": "%s"
                                  }
                                }
                                """.formatted(
                                taskPatchDto.taskId(),
                                taskPatchDto.dateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                taskPatchDto.changes().entrySet().stream().findFirst().get().getKey(),
                                taskPatchDto.changes().entrySet().stream().findFirst().get().getValue()
                        ))
                )
                .andExpect(status().isNotFound());

        verify(taskPatchService).patch(taskPatch);
    }

    @Test
    void undoPatch_success() throws Exception {
        var taskPatch = createRandomTaskPatch();

        when(taskPatchService.findById(taskPatch.getId())).thenReturn(Optional.of(taskPatch));

        mockMvc.perform(delete("/api/task-patches/" + taskPatch.getId()))
                .andExpect(status().isOk());

        verify(taskPatchService).findById(taskPatch.getId());
        verify(taskPatchService).undoPatch(taskPatch);
    }

    @Test
    void undoPatch_404() throws Exception {
        var taskPatch = Instancio.create(TaskPatch.class);

        when(taskPatchService.findById(taskPatch.getId())).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/task-patches/" + taskPatch.getId()))
                .andExpect(status().isNotFound());

        verify(taskPatchService).findById(taskPatch.getId());
    }
}
