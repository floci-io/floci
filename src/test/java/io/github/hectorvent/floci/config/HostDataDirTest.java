package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class HostDataDirTest {

    @Test
    void linuxUsesXdgDataHomeWhenSet() {
        assertThat(HostDataDir.forPlatform("Linux", Map.of("XDG_DATA_HOME", "/srv/xdg"), "/home/u"),
                equalTo(Path.of("/srv/xdg", "floci-aws")));
    }

    @Test
    void linuxFallsBackToLocalShare() {
        assertThat(HostDataDir.forPlatform("Linux", Map.of("XDG_DATA_HOME", " "), "/home/u"),
                equalTo(Path.of("/home/u", ".local", "share", "floci-aws")));
    }

    @Test
    void macUsesApplicationSupport() {
        assertThat(HostDataDir.forPlatform("Mac OS X", Map.of(), "/Users/u"),
                equalTo(Path.of("/Users/u", "Library", "Application Support", "floci-aws")));
    }

    @Test
    void windowsUsesLocalAppData() {
        assertThat(HostDataDir.forPlatform("Windows 11", Map.of("LOCALAPPDATA", "C:/Users/u/AppData/Local"), "C:/Users/u"),
                equalTo(Path.of("C:/Users/u/AppData/Local", "floci-aws")));
        assertThat(HostDataDir.forPlatform("Windows 11", Map.of(), "C:/Users/u"),
                equalTo(Path.of("C:/Users/u", "AppData", "Local", "floci-aws")));
    }

    @Test
    void jvmRunsKeepTheBundledDefault() {
        // The test JVM is not a native image, so nothing moves.
        assertThat(HostDataDir.persistentPathDefault(), equalTo("./data"));
    }
}
