package be.stijnhooft.task.backend.task.mother;

import be.stijnhooft.task.backend.task.dto.TaskPatchDto;
import org.instancio.Instancio;

import java.util.Map;

import static org.instancio.Select.field;

public class TaskPatchDtoMother {

    public static TaskPatchDto createRandomTaskPatchDto() {
        return Instancio.of(TaskPatchDto.class)
                .create();
    }
    public static TaskPatchDto createRandomTaskPatchDto(Map<String, Object> changes) {
        return Instancio.of(TaskPatchDto.class)
                .set(field("changes"), changes)
                .create();
    }
}
