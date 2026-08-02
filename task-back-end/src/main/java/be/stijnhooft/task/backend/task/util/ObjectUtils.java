package be.stijnhooft.task.backend.task.util;

import lombok.SneakyThrows;

import java.lang.reflect.Field;
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
