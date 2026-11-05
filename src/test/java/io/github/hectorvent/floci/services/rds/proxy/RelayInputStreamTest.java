package io.github.hectorvent.floci.services.rds.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayInputStreamTest {

    @Test
    void isNotDrainedWhileBytesRemainInTheBuffer() throws IOException {
        SegmentedInput socket = new SegmentedInput(new byte[] {1, 2, 3}, Integer.MAX_VALUE);
        RelayInputStream relay = new RelayInputStream(socket);

        assertEquals(1, relay.read());
        socket.availableCalls.set(0);

        assertFalse(relay.isDrained());
        assertEquals(0, socket.availableCalls.get(), "a non-empty buffer must not ask the socket");
    }

    @Test
    void isDrainedOnceTheBufferIsEmptyAndTheSocketHasNothingReadable() throws IOException {
        RelayInputStream relay = new RelayInputStream(new SegmentedInput(new byte[] {1, 2, 3}, Integer.MAX_VALUE));

        assertArrayEquals(new byte[] {1, 2, 3}, relay.readNBytes(3));

        assertTrue(relay.isDrained());
    }

    @Test
    void isNotDrainedWhenTheSocketHasMoreToRead() throws IOException {
        SegmentedInput socket = new SegmentedInput(new byte[] {1, 2, 3}, 1);
        RelayInputStream relay = new RelayInputStream(socket);

        assertEquals(1, relay.read());

        assertFalse(relay.isDrained());
        assertEquals(1, socket.availableCalls.get());
    }

    /** Hands out at most {@code segment} bytes per read, as a socket hands out one segment. */
    private static final class SegmentedInput extends ByteArrayInputStream {

        private final int segment;
        final AtomicInteger availableCalls = new AtomicInteger();

        SegmentedInput(byte[] bytes, int segment) {
            super(bytes);
            this.segment = segment;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            return super.read(b, off, Math.min(len, segment));
        }

        @Override
        public synchronized int available() {
            availableCalls.incrementAndGet();
            return super.available();
        }
    }
}
