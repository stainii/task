package be.stijnhooft.task.backend.template.util;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// Reading and substituting `${…}` placeholders in a definition's text.
///
/// **There is no declared list of variable names.** Anything matching `${…}` in a definition's name
/// or description *is* a variable ([ADR-0013 §188](../../../../../../../../docs/adr/0013-one-anchor-and-a-trigger-that-shapes-the-form.md)),
/// which is why `variableNames` left the model with [#47](https://github.com/stainii/task/issues/47).
/// Portal kept both a list and the placeholders and they drifted, so one template asked for a
/// `${lector}` that appeared in no text and threw the answer away for years. With inference that
/// state cannot be represented.
///
/// [#variablesIn] is inference read back out: the authoring form's chips, and the save-time
/// validation that keeps `${…}` out of a scheduled template. It is the *same* pattern the
/// substitution below understands, deliberately in one class — a validator that disagrees with the
/// renderer about what a placeholder is would pass a template the renderer then cannot fill.
public interface VariableUtils {

    /// `${` up to the first `}`, with at least one character between them. `${}` is text, not a
    /// variable with an empty name, so it needs no answer and cannot be typed into the form.
    Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /// Every variable named in these texts, **in the order they first appear**, so the authoring
    /// form's chips read in the order they were typed rather than in hash order. Nulls are skipped:
    /// a definition without a description simply names no variables there.
    static Set<String> variablesIn(@Nullable String... texts) {
        var variables = new LinkedHashSet<String>();
        for (String text : texts) {
            if (text != null) {
                PLACEHOLDER.matcher(text).results().forEach(match -> variables.add(match.group(1)));
            }
        }
        return variables;
    }

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
