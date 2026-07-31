package be.stijnhooft.task.backend.utils;

import lombok.NonNull;

import java.util.Map;
import java.util.Optional;

public interface VariableUtils {
    static Optional<String> fillInVariables(String text, @NonNull Map<String, String> variables) {
        if (text == null) {
            return Optional.empty();
        }

        for (String variableName : variables.keySet()) {
            String variableValue = variables.get(variableName);
            if (variableValue == null) {
                variableValue = "";
            }

            text = text.replace("${" + variableName + "}", variableValue);
        }

        return Optional.of(text);
    }
}
