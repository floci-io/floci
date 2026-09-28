package io.github.hectorvent.floci.services.ses;

import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Header;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.stream.Field;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * The content scan SES runs on every accepted message. Real SES scans for viruses and rejects the
 * whole message with a {@code Reject} event; Floci reproduces that for the one input AWS documents
 * for testing it, the EICAR test file, and nothing else.
 *
 * <p>The EICAR string is a signature that endpoint protection quarantines on sight, so it is never
 * written out verbatim: it is assembled from fragments at class initialisation, must never be
 * logged, and is exposed to tests only through {@link #signature()} so no test source carries it
 * either. A message that trips the scan is likewise never persisted with its body.
 */
final class SesContentScan {

    private static final Logger LOG = Logger.getLogger(SesContentScan.class);

    // The fragments only mean something once joined, which keeps this source file below the
    // detection threshold of the scanners the joined string is designed to trigger.
    private static final String[] FRAGMENTS = {
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$",
            "EICAR-STANDARD-",
            "ANTIVIRUS-TEST-",
            "FILE!$H+H*"
    };
    private static final String SIGNATURE_TEXT = String.join("", FRAGMENTS);
    private static final byte[] SIGNATURE = SIGNATURE_TEXT.getBytes(StandardCharsets.US_ASCII);
    // Decoded parts are read as a stream in chunks of this size with a rolling overlap, so the
    // scan itself never needs a whole part in one array, whatever the parser buffers.
    static final int CHUNK_SIZE = 64 * 1024;

    private SesContentScan() {}

    /** The assembled test string, for tests that need to send it. Never log or persist it. */
    static String signature() {
        return SIGNATURE_TEXT;
    }

    /** True when any of the given texts carries the test signature. Nulls are skipped. */
    static boolean containsTestVirus(String... texts) {
        return containsTestVirus(Arrays.asList(texts));
    }

    /** True when any of the given texts carries the test signature. Nulls are skipped. */
    static boolean containsTestVirus(Iterable<String> texts) {
        for (String text : texts) {
            if (text != null && text.contains(SIGNATURE_TEXT)) {
                return true;
            }
        }
        return false;
    }

    /** The outcome of scanning a raw message. */
    enum Result {
        CLEAN,
        REJECTED,
        /** The parser could not read the message, so only the wire bytes were checked. */
        UNREADABLE
    }

    /** Scans a raw message from its wire bytes and the parse of them, {@code null} when it failed. */
    static Result scan(byte[] mime, Message parsed) {
        if (mime == null || mime.length == 0) {
            return Result.CLEAN;
        }
        // The wire bytes are scanned first: a lenient MIME parse can swallow a headerless payload
        // as malformed header lines, and a plain copy is found either way. An encoded copy the
        // parser cannot reach is one no mail client would decode either, so it is not looked for.
        if (indexOf(mime, SIGNATURE) >= 0) {
            return Result.REJECTED;
        }
        if (parsed == null) {
            return Result.UNREADABLE;
        }
        try {
            return entityContains(parsed) ? Result.REJECTED : Result.CLEAN;
        } catch (IOException | RuntimeException e) {
            LOG.warnv("SES content scan could not walk the parsed message, only the wire bytes were checked: {0}",
                    e.getClass().getName());
            return Result.UNREADABLE;
        }
    }

    /** Scans a raw message, parsing it first; the parse is shared with the send path elsewhere. */
    static Result scan(byte[] mime) {
        if (mime == null || mime.length == 0) {
            return Result.CLEAN;
        }
        return scan(mime, SmtpRelay.parseMime(mime));
    }

    // An explicit worklist rather than recursion: a message can nest forwarded messages and
    // multiparts as deep as its size allows, and the stack must not be the limit.
    private static boolean entityContains(Entity root) throws IOException {
        Deque<Entity> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Entity entity = pending.pop();
            if (headerContains(entity.getHeader())) {
                return true;
            }
            Body body = entity.getBody();
            // A forwarded message (message/rfc822) is a Message body, neither multipart nor a leaf.
            if (body instanceof Message embedded) {
                pending.push(embedded);
            } else if (body instanceof Multipart multipart) {
                for (Entity part : multipart.getBodyParts()) {
                    pending.push(part);
                }
            } else if (body instanceof SingleBody single) {
                try (InputStream in = single.getInputStream()) {
                    if (streamContains(in, SIGNATURE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Header values are scanned decoded: an RFC 2047 encoded word puts the signature behind an
    // encoding the wire scan cannot see through.
    private static boolean headerContains(Header header) {
        if (header == null) {
            return false;
        }
        for (Field field : header.getFields()) {
            String decoded = DecoderUtil.decodeEncodedWords(field.getBody(), DecodeMonitor.SILENT);
            if (decoded != null && decoded.contains(SIGNATURE_TEXT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Searches the whole stream for {@code needle} while holding at most one chunk plus the
     * {@code needle.length - 1} bytes carried over from the previous chunk, so a match that
     * straddles a chunk boundary is still found.
     */
    static boolean streamContains(InputStream in, byte[] needle) throws IOException {
        int overlap = needle.length - 1;
        byte[] window = new byte[overlap + CHUNK_SIZE];
        int carried = 0;
        int read;
        while ((read = in.read(window, carried, CHUNK_SIZE)) > 0) {
            int filled = carried + read;
            if (indexOf(window, filled, needle) >= 0) {
                return true;
            }
            carried = Math.min(overlap, filled);
            System.arraycopy(window, filled - carried, window, 0, carried);
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, haystack.length, needle);
    }

    private static int indexOf(byte[] haystack, int length, byte[] needle) {
        outer:
        for (int i = 0; i <= length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
