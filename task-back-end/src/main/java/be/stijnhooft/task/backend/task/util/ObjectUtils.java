package be.stijnhooft.task.backend.task.util;

import lombok.SneakyThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

public interface ObjectUtils {

    @SneakyThrows
    static Map<String, String> getAllFieldsAndTheirValues(Object object) {
        Map<String, String> allFieldsAndTheirValues = new HashMap<>();
        for (Field field : object.getClass().getDeclaredFields()) {
            // protection against synthetic fields added by frameworks like Lombok, PIT, ...
            if (field.isSynthetic() || field.getName().startsWith("$$")) {
                continue;
            }

            // A static field is not a field *of this object*, and asking whether the instance can
            // access one throws. Nothing here had a static field until the fold's comparator, and
            // the first one turned this method into a hard failure at task creation.
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            boolean wasAccessible = field.canAccess(object);
            field.setAccessible(true);

            String fieldName = field.getName();
            Object fieldValue = field.get(object);

            if (fieldValue != null) {
                allFieldsAndTheirValues.put(fieldName, fieldValue.toString());
            }

            field.setAccessible(wasAccessible);
        }
        return allFieldsAndTheirValues;
    }

}
