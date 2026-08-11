package be.stijnhooft.task.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import java.nio.file.Path;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskBackEndApplicationTests extends AbstractIntegrationTestCases {

    private static final Path MODULE_DOCS = Path.of("../docs/modules");

    @Test
    void contextLoads() {
    }

    @Test
    void verifiesModularStructure() {
        ApplicationModules modules = ApplicationModules.of(TaskBackEndApplication.class);
        modules.verify();

        // written into the repo, not target/, so the module graph is reviewable in a diff — see ADR-0003
        new Documenter(modules, Documenter.Options.defaults().withOutputFolder(MODULE_DOCS.toString()))
                .writeDocumentation();

        // ...and sorted, because Documenter's own order is not stable — D7. See ModuleDocumentation.
        ModuleDocumentation.sortGeneratedLists(MODULE_DOCS);
    }

}
