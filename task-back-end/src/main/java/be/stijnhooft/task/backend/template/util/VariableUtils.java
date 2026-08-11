package be.stijnhooft.task.backend.template.util;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/// Substituting `${…}` placeholders in a definition's text.
///
/// **There is no declared list of variable names.** Anything matching `${…}` in a definition's name
/// or description *is* a variable ([ADR-0013 §188](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)),
/// which is why `variableNames` left the model with this ticket. Portal kept both a list and the
/// placeholders and they drifted, so one template asked for a `${lector}` that appeared in no text
/// and threw the answer away for years. With inference that state cannot be represented.
///
/// Reading the placeholders back out — for the authoring form's chips, and for the save-time
/// validation that keeps `${…}` out of a scheduled template — arrives with
/// [#50](https://github.com/stainii/task/issues/50).
public interface VariableUtils {

    static Optional<String> fillInVariables(@Nullable String text, Map<String, String> variables) {
        if (text == null) {
            return Optional.empty();
        }

        var filled = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            var value = variable.getValue();
            filled = filled.replace("${" + variable.getKey() + "}", value == null ? "" : value);
        }

        return Optional.of(filled);
    }
}
