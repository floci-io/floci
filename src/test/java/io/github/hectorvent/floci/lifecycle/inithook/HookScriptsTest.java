package io.github.hectorvent.floci.lifecycle.inithook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class HookScriptsTest {

    @Test
    void unixCommandsMatchTheImage() {
        assertThat(HookScripts.command("01.sh", false, "/bin/sh"), equalTo(List.of("/bin/sh", "01.sh")));
        assertThat(HookScripts.command("01.py", false, "/bin/sh"), equalTo(List.of("python3", "01.py")));
    }

    @Test
    void windowsCommandsUseNativeInterpreters() {
        assertThat(HookScripts.command("01.ps1", true, "/bin/sh"),
                equalTo(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "01.ps1")));
        assertThat(HookScripts.command("01.bat", true, "/bin/sh"), equalTo(List.of("cmd", "/c", "01.bat")));
        assertThat(HookScripts.command("01.py", true, "/bin/sh"), equalTo(List.of("python", "01.py")));
    }

    @Test
    void runnableDependsOnPlatformAndShell() {
        assertThat(HookScripts.canRun("01.sh", false, "/bin/sh"), is(true));
        assertThat(HookScripts.canRun("01.ps1", false, "/bin/sh"), is(false));
        assertThat(HookScripts.canRun("01.sh", true, "/bin/sh"), is(false));
        assertThat(HookScripts.canRun("01.sh", true, "bash.exe"), is(true));
        assertThat(HookScripts.canRun("notes.txt", true, "/bin/sh"), is(false));
    }

    @Test
    void windowsMatchesExtensionsInAnyCase() {
        assertThat(HookScripts.command("SETUP.BAT", true, "/bin/sh"), equalTo(List.of("cmd", "/c", "SETUP.BAT")));
        assertThat(HookScripts.canRun("Init.PS1", true, "/bin/sh"), is(true));
        assertThat(HookScripts.isScript("SEED.SH", false), is(false));
        assertThat(HookScripts.isScript("seed.sh", false), is(true));
    }

    @Test
    void windowsIsDetectedFromOsName() {
        assertThat(HookScripts.isWindows("Windows Server 2022"), is(true));
        assertThat(HookScripts.isWindows("Mac OS X"), is(false));
        assertThat(HookScripts.isWindows(null), is(false));
    }
}
