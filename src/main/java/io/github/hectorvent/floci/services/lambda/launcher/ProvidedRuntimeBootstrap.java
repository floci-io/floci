package io.github.hectorvent.floci.services.lambda.launcher;

/**
 * Keeps the image's runtime entrypoint while executing the supplied bootstrap in
 * its package or layer directory. Moving the supplied file changes its {@code $0}
 * and breaks runtimes that locate companion files relative to that path.
 */
public final class ProvidedRuntimeBootstrap {

    public static final String SCRIPT = """
            #!/bin/sh
            set -eu
            if [ -e /var/task/bootstrap ]; then
                exec /var/task/bootstrap "$@"
            fi
            if [ -e /opt/bootstrap ]; then
                exec /opt/bootstrap "$@"
            fi
            echo 'no bootstrap found at /var/task/bootstrap or /opt/bootstrap' >&2
            exit 127
            """;

    private ProvidedRuntimeBootstrap() {
    }
}
