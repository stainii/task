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
        var readTask = taskRepository.findById(task.getId());

        assertThat(readTask.get().getHistory().get(0).getId()).isEqualTo(task.getHistory().get(0).getId());
        assertThat(readTask.get().getHistory().get(1).getId()).isEqualTo(task.getHistory().get(1).getId());
        assertThat(readTask.get().getHistory().get(2).getId()).isEqualTo(task.getHistory().get(2).getId());
    }

}
