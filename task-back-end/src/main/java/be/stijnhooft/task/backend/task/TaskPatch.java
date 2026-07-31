package be.stijnhooft.task.backend.task;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jspecify.annotations.NonNull;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/// Class that contains updates of specific fields of a task.
/// These changes should have been reflected in the task.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "version")
public class TaskPatch {

    @Id
    @NonNull
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @NonNull
    private UUID taskId;

    @NonNull
    private LocalDateTime dateTime;

    @NonNull
    private Map<String, String> changes;

    @Version
    @JsonIgnore
    private long version;

    @JsonAnySetter
    public TaskPatch addChange(String key, Object value) {
        if (changes == null) {
            changes = new LinkedHashMap<>();
        }
        changes.put(key, value == null ? null : value.toString());

        return this;
    }

    public boolean containsChange(String field) {
        if (changes == null) {
            return false;
        }
        return changes.containsKey(field);
    }

    public String getChange(String field) {
        return changes.get(field);
    }

    public static class TaskPatchBuilder {
        public TaskPatchBuilder change(String key, Object value) {
            if (this.changes == null) {
                this.changes = new LinkedHashMap<>();
            }
            this.changes.put(key, value == null ? null : value.toString());
            return this;
        }
    }
}
