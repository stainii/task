package be.stijnhooft.task.backend.config.jdbcconverters;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.data.jdbc.core.mapping.JdbcValue;

import java.sql.JDBCType;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonToMapConverterTest {

    private final JsonToMapConverter converter = new JsonToMapConverter();

    @Test
    void shouldConvertSimpleJsonToMap() {
        String json = """
                {
                  "key1": "value1",
                  "key2": "value2"
                }
                """;

        Map<String, String> result = converter.convert(jsonStringToPgObject(json));

        assertThat(result)
                .hasSize(2)
                .containsEntry("key1", "value1")
                .containsEntry("key2", "value2");
    }

    @Test
    void shouldConvertEmptyJsonObjectToEmptyMap() {
        Map<String, String> result = converter.convert(jsonStringToPgObject("{}"));
        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleSpecialCharacters() {
        String json = """
                {
                  "spaced key": "value with spaces",
                  "unicode": "€漢字",
                  "symbols": "!@#$%^&*()"
                }
                """;

        Map<String, String> result = converter.convert(jsonStringToPgObject(json));

        assertThat(result)
                .containsEntry("spaced key", "value with spaces")
                .containsEntry("unicode", "€漢字")
                .containsEntry("symbols", "!@#$%^&*()");
    }

    @Test
    void shouldPreserveDateTimeStrings() {
        String json = """
                {
                  "isoDate": "2026-02-04",
                  "isoDateTime": "2026-02-04T14:30:15",
                  "isoOffsetDateTime": "2026-02-04T14:30:15+01:00",
                  "utcInstant": "2026-02-04T13:30:15Z"
                }
                """;

        Map<String, String> result = converter.convert(jsonStringToPgObject(json));

        assertThat(result)
                .containsEntry("isoDate", "2026-02-04")
                .containsEntry("isoDateTime", "2026-02-04T14:30:15")
                .containsEntry("isoOffsetDateTime", "2026-02-04T14:30:15+01:00")
                .containsEntry("utcInstant", "2026-02-04T13:30:15Z");
    }

    @Test
    void shouldThrowExceptionForInvalidJson() {
        String invalidJson = "{ not-valid-json }";

        assertThatThrownBy(() -> converter.convert(jsonStringToPgObject(invalidJson)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void whenSourceIsNullConvertToNull() {
        var map = converter.convert(jsonStringToPgObject("null"));
        assertThat(map).isNull();
    }

    @Test
    void whenSourceIsEmptyObjectConvertToEmptyMap() {
        var map = converter.convert(jsonStringToPgObject("{}"));
        assertThat(map).isEmpty();
    }

    @SneakyThrows
    private PGobject jsonStringToPgObject(String json) {
        var pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue(json);
        return pgObject;
    }
}
