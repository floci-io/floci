package io.github.hectorvent.floci.core.common;

/**
 * Interface for services or components that hold in-memory state
 * and need to be cleared when the emulator state is reset or nuked.
 */
public interface Resettable {
    void clear();
}
