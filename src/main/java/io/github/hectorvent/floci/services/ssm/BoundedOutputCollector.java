package io.github.hectorvent.floci.services.ssm;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Accumulates UTF-8 bytes into a character buffer bounded to a maximum number of characters.
 * Bytes received after the cap is reached are decoded and discarded rather than retained, so
 * memory stays proportional to {@code maxChars} regardless of how much input is fed in.
 *
 * <p>Handles a multi-byte UTF-8 character split across separate {@link #write(byte[])} calls
 * (e.g. Docker exec stream frames) by carrying any undecoded trailing bytes over to the next
 * call instead of corrupting or dropping the character.
 *
 * <p>Thread-safe: Docker callback threads write while the executor thread may read after a timeout.
 */
final class BoundedOutputCollector {

    private static final byte[] NO_BYTES = new byte[0];

    private final int maxChars;
    private final StringBuilder captured;
    private final CharsetDecoder decoder;
    private byte[] pending = NO_BYTES;
    private boolean capped;

    BoundedOutputCollector(int maxChars) {
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars must be >= 0");
        }
        this.maxChars = maxChars;
        this.captured = new StringBuilder(Math.min(maxChars, 4096));
        this.decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.capped = maxChars == 0;
    }

    synchronized void write(byte[] payload) {
        if (payload == null || payload.length == 0 || capped) {
            return;
        }

        byte[] combined = pending.length == 0 ? payload : concat(pending, payload);
        ByteBuffer in = ByteBuffer.wrap(combined);
        CharBuffer out = CharBuffer.allocate(combined.length + 1);
        decoder.decode(in, out, false);
        out.flip();

        int remaining = maxChars - captured.length();
        if (out.length() >= remaining) {
            if (remaining > 0 && Character.isHighSurrogate(out.charAt(remaining - 1))) {
                remaining--;
            }
            captured.append(out, 0, remaining);
            capped = true;
            pending = NO_BYTES;
            return;
        }

        captured.append(out);
        pending = new byte[in.remaining()];
        in.get(pending);
    }

    synchronized String content() {
        if (pending.length > 0 && captured.length() < maxChars) {
            return captured + "\uFFFD";
        }
        return captured.toString();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
