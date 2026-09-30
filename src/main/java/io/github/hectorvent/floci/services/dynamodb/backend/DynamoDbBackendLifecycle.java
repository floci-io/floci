package io.github.hectorvent.floci.services.dynamodb.backend;

/** The selected engine's process lifecycle, driven by {@code DynamoDbRuntime}. */
public interface DynamoDbBackendLifecycle {

    /** Initializes the engine and its maintenance work; throws when it cannot serve requests. */
    void start();

    /** Throws to refuse an emulator-wide reset; runs before anything is stopped or cleared. */
    void checkReset();

    /** Quiesces maintenance work before Floci storage is cleared. */
    void beforeReset();

    /** Drops the engine's process state; runs after Floci storage was cleared. */
    void reset();

    /** Resumes maintenance work at the end of a reset; may run without a preceding {@link #beforeReset()}. */
    void afterReset();

    /** Stops maintenance work and closes clients. */
    void stop();
}
