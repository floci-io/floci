package io.github.hectorvent.floci.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** File system checks for tests that depend on what the host's file system supports. */
public final class FileSystemAssumptions {

    private FileSystemAssumptions() {}

    /**
     * Skips the calling test unless {@code path}'s file store supports POSIX permissions. Call it
     * before any POSIX permission call: those throw on a file system without them, such as Windows,
     * which would fail the test instead of skipping it.
     */
    public static void assumePosixPermissions(Path path) throws IOException {
        assumeTrue(Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class),
                "POSIX file permissions are not supported here");
    }
}
