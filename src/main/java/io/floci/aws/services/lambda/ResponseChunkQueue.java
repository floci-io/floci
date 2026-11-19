package io.floci.aws.services.lambda;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** The chunks of a streamed response, handed from the runtime thread to the thread writing the body. */
final class ResponseChunkQueue {

    private static final byte[] END = new byte[0];

    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();

    void add(byte[] chunk) {
        chunks.add(chunk);
    }

    /** The function has returned, whether or not it streamed anything. */
    void end() {
        chunks.add(END);
    }

    /** Writes each chunk as it arrives, until {@link #end()}. */
    void drainTo(OutputStream output) throws IOException {
        try {
            for (byte[] chunk = chunks.take(); chunk != END; chunk = chunks.take()) {
                output.write(chunk);
                output.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
