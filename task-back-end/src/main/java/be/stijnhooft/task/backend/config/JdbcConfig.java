package be.stijnhooft.task.backend.config;

import be.stijnhooft.task.backend.config.jdbcconverters.JsonToMapConverter;
import be.stijnhooft.task.backend.config.jdbcconverters.MapToJsonConverter;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

import java.util.List;

@Configuration
public class JdbcConfig extends AbstractJdbcConfiguration {

    @Override
    protected List<?> userConverters() {
        return List.of(
                new MapToJsonConverter(),
                new JsonToMapConverter()
        );
    }

}
