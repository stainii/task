package be.stijnhooft.task.backend.config.jdbcconverters;

import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@ReadingConverter
public class JsonToMapConverter implements Converter<PGobject, Map<String, String>> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final TypeReference<Map<String, String>> TYPE =
            new TypeReference<>() {
            };

    @Override
    public Map<String, String> convert(PGobject source) {
        if (source == null || source.getValue() == null) {
            return null;
        }
        return MAPPER.readValue(source.getValue(), TYPE);
    }
}
