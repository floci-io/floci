package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbBackendLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Drives the selected DynamoDB backend through the emulator lifecycle: readiness at startup,
 * reset preflight, quiescing and cleanup, and shutdown. Does nothing while DynamoDB is disabled.
 */
@ApplicationScoped
public class DynamoDbRuntime implements Resettable {

    private final DynamoDbBackendLifecycle backend;
    private final EmulatorConfig config;

    @Inject
    public DynamoDbRuntime(DynamoDbBackendLifecycle backend, EmulatorConfig config) {
        this.backend = backend;
        this.config = config;
    }

    public void start() {
        if (enabled()) {
            backend.start();
        }
    }

    public void stop() {
        if (enabled()) {
            backend.stop();
        }
    }

    @Override
    public void checkReset() {
        if (enabled()) {
            backend.checkReset();
        }
    }

    @Override
    public void beforeReset() {
        if (enabled()) {
            backend.beforeReset();
        }
    }

    @Override
    public void clear() {
        if (enabled()) {
            backend.reset();
        }
    }

    @Override
    public void afterReset() {
        if (enabled()) {
            backend.afterReset();
        }
    }

    private boolean enabled() {
        return config.services().dynamodb().enabled();
    }
}
