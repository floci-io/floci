package io.floci.aws.services.codeartifact;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * The lockstep, fixed-buffer byte comparison every content-match preflight across the four
 * sidecar write paths shares (the generic copy path, NuGet's and pypi's own direct-publish
 * preflights, and Maven's {@code ReposiliteSidecarClient}): read a chunk from each side with
 * {@code readNBytes} so a short individual {@code read()} on either stream can never be mistaken
 * for the two genuinely disagreeing, and stop as soon as either a length mismatch or a content
 * mismatch is found. Neither stream is closed here: each caller already owns that in its own
 * try-with-resources, since what counts as "done with" either stream differs by caller.
 */
final class StreamEquality {

    private StreamEquality() {
    }

    static boolean contentsMatch(InputStream a, InputStream b) throws IOException {
        byte[] bufferA = new byte[8192];
        byte[] bufferB = new byte[8192];
        while (true) {
            int readA = a.readNBytes(bufferA, 0, bufferA.length);
            int readB = b.readNBytes(bufferB, 0, bufferB.length);
            if (readA != readB) {
                return false;
            }
            if (readA == 0) {
                return true;
            }
            if (!Arrays.equals(bufferA, 0, readA, bufferB, 0, readB)) {
                return false;
            }
        }
    }
}
