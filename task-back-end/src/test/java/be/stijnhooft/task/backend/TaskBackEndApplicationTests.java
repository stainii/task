package be.stijnhooft.task.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskBackEndApplicationTests extends AbstractIntegrationTestCases {

    @Test
    void contextLoads() {
    }

    @Test
    void verifiesModularStructure() {
        ApplicationModules modules = ApplicationModules.of(TaskBackEndApplication.class);
        modules.verify();
        new Documenter(modules)
                .writeDocumentation();
    }

}
