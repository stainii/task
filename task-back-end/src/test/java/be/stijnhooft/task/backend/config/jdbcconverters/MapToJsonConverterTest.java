package be.stijnhooft.task.backend.config.jdbcconverters;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MapToJsonConverterTest {

    private final MapToJsonConverter converter = new MapToJsonConverter();

    @Test
    void shouldConvertSimpleMapToJson() {
        Map<String, String> input = Map.of(
                "key1", "value1",
                "key2", "value2"
        );

        String json = converter.convert(input)
                .getValue()
                .toString();

        assertThat(json)
                .contains("\"key1\":\"value1\"")
                .contains("\"key2\":\"value2\"");
    }

    @Test
    void shouldConvertEmptyMapToEmptyJsonObject() {
        String json = converter.convert(Map.of())
                .getValue()
                .toString();
        assertThat(json).isEqualTo("{}");
    }

    @Test
    void shouldHandleSpecialCharacters() {
        Map<String, String> input = Map.of(
                "spaced key", "value with spaces",
                "unicode", "€漢字",
                "symbols", "!@#$%^&*()"
        );

        String json = converter.convert(input)
                .getValue()
                .toString();

        assertThat(json)
                .contains("\"spaced key\":\"value with spaces\"")
                .contains("\"unicode\":\"€漢字\"")
                .contains("\"symbols\":\"!@#$%^&*()\"");
    }

    @Test
    void shouldPreserveDateTimeStrings() {
        Map<String, String> input = Map.of(
                "isoDate", "2026-02-04",
                "isoDateTime", "2026-02-04T14:30:15",
                "isoOffsetDateTime", "2026-02-04T14:30:15+01:00",
                "utcInstant", "2026-02-04T13:30:15Z"
        );

        String json = converter.convert(input)
                .getValue()
                .toString();

        assertThat(json)
                .contains("\"isoDate\":\"2026-02-04\"")
                .contains("\"isoDateTime\":\"2026-02-04T14:30:15\"")
                .contains("\"isoOffsetDateTime\":\"2026-02-04T14:30:15+01:00\"")
                .contains("\"utcInstant\":\"2026-02-04T13:30:15Z\"");
    }

    @Test
    void whenSourceIsNullConvertToNull() {
        String json = converter.convert(null)
                .getValue()
                .toString();
        assertThat(json).isEqualTo("null");
    }
}
