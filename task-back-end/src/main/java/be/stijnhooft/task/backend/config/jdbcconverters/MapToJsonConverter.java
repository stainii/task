package be.stijnhooft.task.backend.config.jdbcconverters;

import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.SneakyThrows;
import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.mapping.JdbcValue;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.JDBCType;
import java.util.Map;

@WritingConverter
public class MapToJsonConverter implements Converter<Map<String, String>, JdbcValue> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    @SneakyThrows
    public JdbcValue convert(Map<String, String> source) {
        var pgObject = new PGobject();
        pgObject.setType("jsonb");
        pgObject.setValue(MAPPER.writeValueAsString(source));

        return JdbcValue.of(pgObject, JDBCType.OTHER);
    }

}
