package be.stijnhooft.task.backend.config.jdbcconverters;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.data.jdbc.core.mapping.JdbcValue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoundTripJsonConverterTest {

    @Test
    void shouldRoundTripMapToJsonAndBack() {
        Map<String, String> original = Map.of(
                "name", "Stijn",
                "createdAt", "2026-02-04T14:30:15+01:00"
        );

        MapToJsonConverter writer = new MapToJsonConverter();
        JsonToMapConverter reader = new JsonToMapConverter();

        JdbcValue json = writer.convert(original);
        Map<String, String> result = reader.convert((PGobject) json.getValue());

        assertThat(result).isEqualTo(original);
    }

}
