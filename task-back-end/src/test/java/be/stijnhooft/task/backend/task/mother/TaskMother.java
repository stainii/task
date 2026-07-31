package be.stijnhooft.task.backend.task.mother;

import be.stijnhooft.task.backend.task.Task;
import be.stijnhooft.task.backend.task.TaskPatch;
import org.instancio.Instancio;

import java.util.UUID;

import static org.instancio.Select.field;

public class TaskMother {

    public static Task createRandomTask() {
        var taskId = UUID.randomUUID();
        var taskPatches = Instancio.ofList(TaskPatch.class)
                .size(3)
                .set(field(TaskPatch::getTaskId), taskId)
                .ignore(field(TaskPatch::getVersion))
                .create();
        return Instancio.of(Task.class)
                .set(field(Task::getId), taskId)
                .set(field(Task::getHistory), taskPatches)
                .ignore(field(Task::getVersion))
                .create();
    }
}
