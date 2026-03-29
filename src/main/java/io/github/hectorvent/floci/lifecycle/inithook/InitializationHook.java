package io.github.hectorvent.floci.lifecycle.inithook;

import java.io.File;

public enum InitializationHook {

    STOP("shutdown", "/etc/floci/init/stop.d"),
    START("startup", "/etc/floci/init/start.d");

    private final String name;
    private final File path;

    InitializationHook(final String name, final String path) {
        this.name = name;
        this.path = new File(path);
    }

    public String getName() {
        return name;
    }

    public File getPath() {
        return path;
    }

}
