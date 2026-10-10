package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.S3Service.CopySourceRange;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The bytes of one copy-source range, read from a stream over the whole source as they are needed.
 * The first read skips to the range's first byte, so nothing of the source is touched until the
 * part is about to be written, and the stream ends after the range's last byte. A source that ends
 * before the range does fails the read.
 */
final class CopyRangeInputStream extends InputStream {

    private final InputStream source;
    private final long first;
    private long remaining;
    private boolean positioned;

    CopyRangeInputStream(InputStream source, CopySourceRange range) {
        this.source = source;
        this.first = range.first();
        this.remaining = range.length();
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read < 0 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (!positioned) {
            source.skipNBytes(first);
            positioned = true;
        }
        if (remaining == 0) {
            return -1;
        }
        int read = source.read(buffer, offset, (int) Math.min(length, remaining));
        if (read < 0) {
            throw new EOFException("Copy source ended " + remaining + " bytes before the end of the requested range");
        }
        remaining -= read;
        return read;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
