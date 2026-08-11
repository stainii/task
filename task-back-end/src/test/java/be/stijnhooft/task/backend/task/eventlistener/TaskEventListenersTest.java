package be.stijnhooft.task.backend.task.eventlistener;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import be.stijnhooft.task.backend.task.TaskCreationRequestedEvent;
import be.stijnhooft.task.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.test.ApplicationModuleTest;

import java.util.List;

import static be.stijnhooft.task.backend.task.mother.TaskMother.createRandomTask;
import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(extraIncludes = "config")
class TaskEventListenersTest extends AbstractIntegrationTestCases {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void handleCreationOfTasksBasedOnTaskTemplateRequested() {
        var amountOfTasksBeforeTest = taskRepository.count();
        var tasks = List.of(createRandomTask(), createRandomTask());
        var taskCreationRequestedEvent = new TaskCreationRequestedEvent(tasks);

        eventPublisher.publishEvent(taskCreationRequestedEvent);

        assertThat(taskRepository.count()).isEqualTo(amountOfTasksBeforeTest + 2);
        assertThat(taskRepository.existsById(tasks.getFirst().id())).isTrue();
        assertThat(taskRepository.existsById(tasks.getLast().id())).isTrue();
    }

}
