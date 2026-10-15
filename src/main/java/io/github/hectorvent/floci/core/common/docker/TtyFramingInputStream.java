package io.github.hectorvent.floci.core.common.docker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Frames raw PTY chunks for docker-java 3.7.1, whose decoder mistakes leading 0, 1, or 2 for a header.
 * The decoder removes these synthetic headers before delivering the original bytes to its callback.
 *
 * @see <a href="https://github.com/docker-java/docker-java/blob/3.7.1/docker-java-core/src/main/java/com/github/dockerjava/core/FramedInputStreamConsumer.java">docker-java's decoder</a>
 */
final class TtyFramingInputStream extends InputStream {

    private final InputStream input;
    private final byte[] frame = new byte[8 + 1024];
    private int position;
    private int limit;

    TtyFramingInputStream(InputStream input) {
        this.input = input;
        frame[0] = 1;
    }

    @Override
    public int read() throws IOException {
        if (position == limit && !nextFrame()) {
            return -1;
        }
        return frame[position++] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        if (position == limit && !nextFrame()) {
            return -1;
        }
        int count = Math.min(length, limit - position);
        System.arraycopy(frame, position, bytes, offset, count);
        position += count;
        return count;
    }

    private boolean nextFrame() throws IOException {
        int count = input.read(frame, 8, frame.length - 8);
        if (count < 0) {
            return false;
        }
        ByteBuffer.wrap(frame, 4, 4).putInt(count);
        position = 0;
        limit = 8 + count;
        return true;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
