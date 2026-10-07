package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decodes an aws-chunked body as it is read, so a streaming upload is never held whole. Each chunk
 * header ({@code hex-size[;chunk-signature=...]}) is consumed and only the chunk data comes out, up
 * to the final empty chunk and the trailer lines and empty line after it. Chunk signatures and
 * trailing checksums are not checked, as they were not when the body was decoded in one piece. A
 * body that breaks the framing or ends early fails with S3's {@code IncompleteBody}.
 */
final class AwsChunkedInputStream extends InputStream {

    // A chunk header or trailer line is a size, a signature and a few separators; anything longer
    // is not framing.
    private static final int MAX_LINE_LENGTH = 8192;

    private final InputStream in;
    private long remainingInChunk;
    private boolean finished;

    AwsChunkedInputStream(InputStream in) {
        this.in = new BufferedInputStream(in);
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
        if (remainingInChunk == 0 && !nextChunk()) {
            return -1;
        }
        int read = in.read(buffer, offset, (int) Math.min(length, remainingInChunk));
        if (read < 0) {
            throw incompleteBody();
        }
        remainingInChunk -= read;
        if (remainingInChunk == 0) {
            readLineBreak();
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Reads the next chunk header; false once the final chunk and its trailer are consumed. */
    private boolean nextChunk() throws IOException {
        if (finished) {
            return false;
        }
        String header = readLine();
        int semicolon = header.indexOf(';');
        String hexSize = (semicolon >= 0 ? header.substring(0, semicolon) : header).trim();
        long size;
        try {
            size = Long.parseLong(hexSize, 16);
        } catch (NumberFormatException e) {
            throw incompleteBody();
        }
        if (size < 0) {
            throw incompleteBody();
        }
        if (size > 0) {
            remainingInChunk = size;
            return true;
        }
        // The final chunk: trailer lines such as x-amz-checksum-crc32, if any, then the empty line
        // that ends the body.
        String trailer = readLine();
        while (!trailer.isEmpty()) {
            trailer = readLine();
        }
        finished = true;
        return false;
    }

    private void readLineBreak() throws IOException {
        int next = in.read();
        if (next == '\r') {
            next = in.read();
        }
        if (next != '\n') {
            throw incompleteBody();
        }
    }

    /** The next line, trimmed and without its line break; the body may not end inside one. */
    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int next = in.read(); next != '\n'; next = in.read()) {
            if (next < 0 || line.size() >= MAX_LINE_LENGTH) {
                throw incompleteBody();
            }
            line.write(next);
        }
        return line.toString(StandardCharsets.ISO_8859_1).trim();
    }

    static AwsException incompleteBody() {
        return new AwsException("IncompleteBody",
                "You did not provide the number of bytes specified by the Content-Length HTTP header.", 400);
    }
}
