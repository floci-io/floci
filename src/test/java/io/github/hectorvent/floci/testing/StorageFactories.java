package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real {@link StorageFactory} instances over a test directory, for tests that shut one down and open
 * another on the same directory to see what a storage mode keeps. The WAL compaction and flush intervals
 * are an hour, so neither timer fires during a test.
 */
public final class StorageFactories {

    private static final long AN_HOUR_MS = 3_600_000L;

    private StorageFactories() {
    }

    /** A factory whose every store uses {@code mode} under {@code directory}, scoped to {@code account}. */
    public static StorageFactory open(Path directory, String mode, String account) {
        return open(config(directory, account), mode);
    }

    /** The deep-stubbed config {@link #open(Path, String, String)} uses, for a test that stubs more of it. */
    public static EmulatorConfig config(Path directory, String account) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(account);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(AN_HOUR_MS);
        return config;
    }

    public static StorageFactory open(EmulatorConfig config, String mode) {
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(AN_HOUR_MS);
        return new StorageFactory(config, access);
    }
}
