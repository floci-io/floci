package io.floci.aws.services.lambdaweb;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts what one output stream of an environment writes into log events. An event keeps its
 * newline. Bytes that are not UTF-8 become U+FFFD, and a line whose text is longer than
 * {@link #MAX_EVENT_BYTES} in UTF-8 is cut before the character that would pass it, without its
 * newline, as on AWS.
 */
final class LambdaWebOutputLines {

    /** The CloudWatch Logs event limit of 256 KiB less the 26 bytes it charges for each event. */
    static final int MAX_EVENT_BYTES = 262_118;

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    /** The start of a character whose other bytes come in the next payload. */
    private ByteBuffer undecoded = ByteBuffer.allocate(0);
    private final StringBuilder pending = new StringBuilder();
    private int pendingBytes;

    List<String> append(byte[] payload) {
        ByteBuffer in = ByteBuffer.allocate(undecoded.remaining() + payload.length).put(undecoded).put(payload).flip();
        CharBuffer out = CharBuffer.allocate(in.remaining());
        decoder.decode(in, out, false);
        undecoded = ByteBuffer.allocate(in.remaining()).put(in).flip();
        return cut(out.flip());
    }

    /** The output after the last newline, when the stream ends without one. */
    List<String> flush() {
        CharBuffer out = CharBuffer.allocate(undecoded.remaining() + 1);
        decoder.decode(undecoded, out, true);
        decoder.flush(out);
        decoder.reset();
        undecoded = ByteBuffer.allocate(0);
        List<String> events = cut(out.flip());
        if (!pending.isEmpty()) {
            events.add(take());
        }
        return events;
    }

    private List<String> cut(CharBuffer text) {
        List<String> events = new ArrayList<>();
        text.codePoints().forEach(codePoint -> {
            int length = utf8Length(codePoint);
            if (MAX_EVENT_BYTES < pendingBytes + length) {
                events.add(take());
            }
            pending.appendCodePoint(codePoint);
            pendingBytes += length;
            if (codePoint == '\n') {
                events.add(take());
            }
        });
        return events;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    private String take() {
        String event = pending.toString();
        pending.setLength(0);
        pendingBytes = 0;
        return event;
    }
}
