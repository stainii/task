package be.stijnhooft.task.backend.task.mother;

import be.stijnhooft.task.backend.task.TaskPatch;
import org.instancio.Instancio;

import static org.instancio.Select.field;

public class TaskPatchMother {

    public static TaskPatch createRandomTaskPatch() {
        return Instancio.of(TaskPatch.class)
                .ignore(field(TaskPatch::getVersion))
                .create();
    }

}
