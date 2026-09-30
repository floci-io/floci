package io.github.hectorvent.floci.core.common;

/**
 * Services with runtime state cleared by emulator reset/nuke. Storage is wiped before clear(),
 * allowing clear() to recreate bootstrap data. Background publishers may additionally quiesce
 * before the wipe and resume afterwards. Hooks must release their locks before returning: the
 * controller never holds StorageFactory's monitor while invoking them.
 */
public interface Resettable {
    /**
     * Throw to refuse an emulator-wide reset. Runs on every service before anything is stopped or
     * cleared, so a refusal leaves all state intact.
     */
    default void checkReset() {}

    /** Stop accepting work and drain active writes before any storage is cleared. */
    default void beforeReset() {}

    void clear();

    /**
     * Resume after reset, including when a storage/clear operation failed or an earlier service's
     * beforeReset() threw, so this may run without a preceding {@link #beforeReset()} call.
     */
    default void afterReset() {}
}
