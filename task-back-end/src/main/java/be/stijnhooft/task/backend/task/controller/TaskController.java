package be.stijnhooft.task.backend.task.controller;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.dto.CreateTaskDto;
import be.stijnhooft.task.backend.task.dto.TaskDto;
import be.stijnhooft.task.backend.task.mapper.TaskMapper;
import be.stijnhooft.task.backend.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskMapper taskMapper;

    @GetMapping
    public List<TaskDto> getAllActiveTasks() {
        return taskService.findAllActiveTasks()
                .stream()
                .map(taskMapper::toDto)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto create(@RequestBody @Valid CreateTaskDto taskDto) {
        var createdTask = taskService.create(taskMapper.toDomain(taskDto));
        return taskMapper.toDto(createdTask);
    }


}
