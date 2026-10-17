package io.github.hectorvent.floci.lifecycle.inithook;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public enum InitializationHook {

    BOOT("boot", "boot", List.of("boot.d"), List.of("boot.d")),
    START("startup", "start", List.of("start.d"), List.of("start.d")),
    READY("ready", "ready", List.of("ready.d"), List.of("ready.d")),
    STOP("shutdown", "shutdown", List.of("stop.d", "shutdown.d"), List.of("shutdown.d"));

    /** Default of {@code floci.init-hooks.dir}: where the image expects hook directories to be mounted. */
    public static final String DEFAULT_ROOT = "/etc/floci/init";
    static final String COMPAT_ROOT = "/etc/localstack/init";

    private final String name;
    private final String responseKey;
    private final List<String> primarySubdirs;
    private final List<File> compatPaths;

    InitializationHook(String name, String responseKey, List<String> primarySubdirs, List<String> compatSubdirs) {
        this.name = name;
        this.responseKey = responseKey;
        this.primarySubdirs = primarySubdirs;
        this.compatPaths = compatSubdirs.stream().map(dir -> new File(COMPAT_ROOT, dir)).toList();
    }

    public String getName() {
        return name;
    }

    /** Key used in the {@code /_floci/init} and {@code /_localstack/init} response body. */
    public String getResponseKey() {
        return responseKey;
    }

    /**
     * Floci-native directories for this phase under {@code root} ({@code floci.init-hooks.dir}). First
     * occurrence of a filename wins.
     */
    public List<File> getPrimaryPaths(String root) {
        return primarySubdirs.stream().map(dir -> Path.of(root, dir).toFile()).toList();
    }

    /** LocalStack-compat directories for this phase. Only used when a filename is not already in a primary path. */
    public List<File> getCompatPaths() {
        return compatPaths;
    }
}
