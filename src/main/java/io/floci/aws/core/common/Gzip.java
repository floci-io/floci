package io.floci.aws.core.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPOutputStream;

/**
 * Gzips content held in memory, as a service does for a log file or a payload it delivers whole.
 * A writer that compresses as it goes, into a file or a stream it hands on, keeps its own
 * {@link GZIPOutputStream}.
 */
public final class Gzip {

    private Gzip() {
    }

    /**
     * {@code data} as one gzip member. Writing to memory cannot fail, so an {@link IOException}
     * from the compressor is rethrown unchecked rather than handed to every caller.
     *
     * <p>The buffer starts small and grows with the compressed output rather than being sized from
     * the input: a log compresses to a small part of its size, and a buffer reserved up front would
     * hold memory the result never fills beside the input the caller already holds.
     */
    public static byte[] compress(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
