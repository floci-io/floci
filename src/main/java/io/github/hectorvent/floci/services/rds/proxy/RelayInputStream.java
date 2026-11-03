package io.github.hectorvent.floci.services.rds.proxy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Buffers a relay's socket input, so a relay that reads message by message (type byte, length,
 * payload) costs one socket read per segment rather than three or more per message.
 */
final class RelayInputStream extends BufferedInputStream {

    private static final int BUFFER_SIZE = 64 * 1024;

    RelayInputStream(InputStream socketInput) {
        super(socketInput, BUFFER_SIZE);
    }

    /**
     * Whether the peer has paused: nothing buffered and nothing readable on the socket. Asks the
     * socket only once the buffer is drained, i.e. once per segment, not once per message.
     */
    boolean isDrained() throws IOException {
        return pos >= count && in.available() == 0;
    }
}
