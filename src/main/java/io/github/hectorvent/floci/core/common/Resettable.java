package io.github.hectorvent.floci.core.common;

/**
 * Services with runtime state cleared by emulator reset/nuke. Storage is wiped before clear(),
 * allowing clear() to recreate bootstrap data. Background publishers may additionally quiesce
 * before the wipe and resume afterwards. Hooks must release their locks before returning: the
 * controller never holds StorageFactory's monitor while invoking them.
 */
public interface Resettable {
    /** Stop accepting work and drain active writes before any storage is cleared. */
    default void beforeReset() {}

    void clear();

    /** Resume after reset, including when a storage/clear operation failed. */
    default void afterReset() {}
}
