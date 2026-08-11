package be.stijnhooft.task.backend.task.repository;

import be.stijnhooft.task.backend.AbstractIntegrationTestCases;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;

import static be.stijnhooft.task.backend.task.mother.TaskMother.createRandomTask;
import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(extraIncludes = "config")
class TaskRepositoryTest extends AbstractIntegrationTestCases {

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void orderOfTasksIsMaintainedWhenSavingPatches() {
        var task = createRandomTask();
        taskRepository.save(task);
        var readTask = taskRepository.findById(task.id());

        assertThat(readTask.get().history().get(0).id()).isEqualTo(task.history().get(0).id());
        assertThat(readTask.get().history().get(1).id()).isEqualTo(task.history().get(1).id());
        assertThat(readTask.get().history().get(2).id()).isEqualTo(task.history().get(2).id());
    }

}
