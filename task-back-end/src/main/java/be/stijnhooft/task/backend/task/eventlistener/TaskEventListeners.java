package be.stijnhooft.task.backend.task.eventlistener;

import be.stijnhooft.task.backend.task.TaskCreationRequestedEvent;
import be.stijnhooft.task.backend.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskEventListeners {

    private final TaskService taskService;

    @EventListener
    public void handleCreationOfTasksBasedOnTaskTemplateRequested(TaskCreationRequestedEvent event) {
        taskService.create(event.tasks());
    }

}
