package io.github.hectorvent.floci.lifecycle.inithook;

import java.util.List;
import java.util.Locale;

/**
 * Which hook scripts can run on this platform, and the command that runs each.
 *
 * <p>On Linux and macOS, {@code .sh} runs with the configured shell and {@code .py} with
 * {@code python3}, as in the image. A native binary on Windows has no {@code /bin/sh}, so there
 * {@code .ps1} runs with PowerShell, {@code .cmd} and {@code .bat} with {@code cmd}, {@code .py}
 * with {@code python}, and {@code .sh} only when a shell other than the default
 * {@code /bin/sh} is configured (Git Bash, for example). Windows file names are case-insensitive, so
 * there {@code SETUP.BAT} is a {@code .bat} hook; elsewhere the extension must be lower case, as
 * before.
 */
final class HookScripts {

    static final String DEFAULT_SHELL = "/bin/sh";

    private HookScripts() {
    }

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Whether the file is a hook script at all, on any platform. */
    static boolean isScript(String fileName, boolean windows) {
        return extensionOf(fileName, windows) != null;
    }

    static boolean canRun(String fileName, boolean windows, String shell) {
        String extension = extensionOf(fileName, windows);
        if (extension == null) {
            return false;
        }
        return switch (extension) {
            case ".sh" -> !windows || !DEFAULT_SHELL.equals(shell);
            case ".py" -> true;
            case ".ps1", ".cmd", ".bat" -> windows;
            default -> false;
        };
    }

    static List<String> command(String fileName, boolean windows, String shell) {
        String extension = extensionOf(fileName, windows);
        if (extension == null) {
            throw new IllegalArgumentException("Not a hook script: " + fileName);
        }
        return switch (extension) {
            case ".py" -> List.of(windows ? "python" : "python3", fileName);
            case ".ps1" -> List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", fileName);
            case ".cmd", ".bat" -> List.of("cmd", "/c", fileName);
            default -> List.of(shell, fileName);
        };
    }

    private static String extensionOf(String fileName, boolean windows) {
        String name = windows ? fileName.toLowerCase(Locale.ROOT) : fileName;
        for (String extension : List.of(".sh", ".py", ".ps1", ".cmd", ".bat")) {
            if (name.endsWith(extension)) {
                return extension;
            }
        }
        return null;
    }
}
