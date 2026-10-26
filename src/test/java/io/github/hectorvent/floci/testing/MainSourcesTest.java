package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainSourcesTest {

    @Test
    void basePackageIsTheParentOfConfig() {
        assertEquals(EmulatorConfig.class.getPackageName(), MainSources.BASE_PACKAGE + ".config");
        assertTrue(Files.isDirectory(MainSources.BASE_DIR.resolve("services")), MainSources.BASE_DIR.toString());
    }

    @Test
    void sourceFileOfATopLevelClassExists() {
        assertTrue(Files.isRegularFile(MainSources.sourceFile(EmulatorConfig.class)),
                MainSources.sourceFile(EmulatorConfig.class).toString());
        assertEquals(MainSources.BASE_DIR.resolve("config"), MainSources.packageDir(EmulatorConfig.class));
    }

    @Test
    void sourceFileRejectsANestedClass() {
        assertThrows(IllegalArgumentException.class, () -> MainSources.sourceFile(EmulatorConfig.ServicesConfig.class));
    }
}
