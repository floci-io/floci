package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.SesContentScan.Result;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test signature is obtained from {@link SesContentScan#signature()} and never spelled out
 * here, so this source file is as safe to check out as the production one.
 */
class SesContentScanTest {

    private static final String SIGNATURE = SesContentScan.signature();

    @Test
    void assembledSignatureHasTheDocumentedLength() {
        assertEquals(68, SIGNATURE.length());
    }

    @Test
    void cleanTextIsNotFlagged() {
        assertFalse(SesContentScan.containsTestVirus("hello", "<p>hello</p>", null));
    }

    @Test
    void signatureInsideATextBodyIsFlagged() {
        assertTrue(SesContentScan.containsTestVirus(null, "prefix " + SIGNATURE + " suffix"));
    }

    @Test
    void signatureInsideAPlainMimeBodyIsFlagged() {
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n\r\n" + SIGNATURE + "\r\n";

        assertEquals(Result.REJECTED, SesContentScan.scan(mime.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signatureInsideAQuotedPrintableAttachmentIsFoundOnlyByDecoding() {
        // Quoted-printable with escaped characters: neither the plain signature nor its base64
        // spelling is on the wire, so only the decoded part walk can find it.
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nsee attachment\r\n"
                + "--b\r\nContent-Type: application/octet-stream; name=\"eicar.com\"\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n"
                + "Content-Disposition: attachment; filename=\"eicar.com\"\r\n\r\n"
                + quotedPrintable(SIGNATURE) + "\r\n--b--\r\n";
        byte[] bytes = mime.getBytes(StandardCharsets.UTF_8);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE),
                "the fixture must only carry the signature in encoded form");
        assertEquals(Result.REJECTED, SesContentScan.scan(bytes));
    }

    @Test
    void signatureStraddlingAChunkBoundaryInsideALargeAttachmentIsFlagged() {
        // The decoded part is longer than one scan chunk and the signature starts 30 bytes
        // before the boundary, so only the carried-over overlap can complete the match. Quoted-
        // printable keeps the signature off the wire, so the match has to come from the stream.
        String decoded = "a".repeat(SesContentScan.CHUNK_SIZE - 30) + SIGNATURE + "a".repeat(4096);
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
                + quotedPrintable(decoded) + "\r\n--b--\r\n";
        byte[] bytes = mime.getBytes(StandardCharsets.UTF_8);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE),
                "the fixture must only carry the signature in encoded form");
        assertEquals(Result.REJECTED, SesContentScan.scan(bytes));
    }

    @Test
    void streamScanFindsASignatureStraddlingTheChunkBoundary() throws IOException {
        // A ByteArrayInputStream hands back exactly the requested chunk, so the signature that
        // starts 30 bytes before CHUNK_SIZE is split across two reads and only the carried-over
        // overlap can complete the match. The MIME test above cannot pin this down because the
        // decoder's own read sizes do not line up with the scan chunk.
        byte[] signature = SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        int prefix = SesContentScan.CHUNK_SIZE - 30;
        byte[] data = new byte[prefix + signature.length + 10];
        Arrays.fill(data, (byte) 'a');
        System.arraycopy(signature, 0, data, prefix, signature.length);

        assertTrue(SesContentScan.streamContains(new ByteArrayInputStream(data), signature));
        assertFalse(SesContentScan.streamContains(new ByteArrayInputStream(new byte[prefix + 200]), signature));
    }

    @Test
    void signatureInsideAForwardedMessageIsFlagged() {
        // message/rfc822 parts surface as an embedded Message body, not a leaf; the signature sits
        // in a quoted-printable attachment of the forwarded message, two levels down, so only the
        // walk into the embedded message can find it.
        String inner = "From: c@example.com\r\nTo: d@example.com\r\nSubject: fwd\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"i\"\r\n\r\n"
                + "--i\r\nContent-Type: text/plain\r\n\r\nforwarded\r\n"
                + "--i\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
                + quotedPrintable(SIGNATURE) + "\r\n--i--\r\n";
        String outer = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"o\"\r\n\r\n"
                + "--o\r\nContent-Type: text/plain\r\n\r\nsee forwarded message\r\n"
                + "--o\r\nContent-Type: message/rfc822\r\n\r\n" + inner + "\r\n--o--\r\n";
        byte[] bytes = outer.getBytes(StandardCharsets.UTF_8);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE),
                "the fixture must only carry the signature in encoded form");
        assertEquals(Result.REJECTED, SesContentScan.scan(bytes));
    }

    @Test
    void signatureInsideAnEncodedWordSubjectIsFoundOnlyByDecodingTheHeader() {
        // An RFC 2047 encoded word carries at most 75 characters, so the signature spans two words
        // and each restarts the base64 alignment: neither the plain nor the base64 wire scan sees it.
        byte[] signature = SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        String first = Base64.getEncoder().encodeToString(Arrays.copyOfRange(signature, 0, 45));
        String second = Base64.getEncoder().encodeToString(Arrays.copyOfRange(signature, 45, signature.length));
        String mime = "From: a@example.com\r\nTo: b@example.com\r\n"
                + "Subject: =?UTF-8?B?" + first + "?=\r\n =?UTF-8?B?" + second + "?=\r\n\r\nclean\r\n";
        byte[] bytes = mime.getBytes(StandardCharsets.UTF_8);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE),
                "the fixture must only carry the signature in encoded form");
        assertEquals(Result.REJECTED, SesContentScan.scan(bytes));
    }

    @Test
    void base64SpellingNothingWouldDecodeIsNotFlagged() {
        // Neither a header value nor a text body without a transfer encoding is decoded by a mail
        // client, so the base64 spelling of the signature in either is plain text, not the file.
        String encoded = Base64.getEncoder().encodeToString(SIGNATURE.getBytes(StandardCharsets.US_ASCII));
        String mime = "From: a@example.com\r\nX-Probe: " + encoded + "\r\n\r\n" + encoded + "\r\n";

        assertEquals(Result.CLEAN, SesContentScan.scan(mime.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void signatureInsideABase64AttachmentIsFoundByDecodingThePart() {
        String encoded = Base64.getMimeEncoder().encodeToString(SIGNATURE.getBytes(StandardCharsets.US_ASCII));
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + encoded + "\r\n--b--\r\n";

        assertEquals(Result.REJECTED, SesContentScan.scan(mime.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void thousandsOfForwardingLayersAreWalked() {
        byte[] bytes = nestedForwards(3_000);

        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(SIGNATURE));
        assertEquals(Result.REJECTED, SesContentScan.scan(bytes));
    }

    @Test
    void nestingBeyondTheParserIsRefusedNotCrashed() {
        // mime4j nests a reader per forwarded message and overflows the stack at this depth; the
        // scan must say so rather than throw an Error or call the message clean.
        assertEquals(Result.UNREADABLE, SesContentScan.scan(nestedForwards(100_000)));
    }

    // message/rfc822 layers around a quoted-printable copy of the signature, so nothing on the
    // wire matches and only a walk to the innermost part can find it.
    private static byte[] nestedForwards(int depth) {
        StringBuilder mime = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            mime.append("Content-Type: message/rfc822\r\n\r\n");
        }
        mime.append("Content-Type: text/plain\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n")
                .append(quotedPrintable(SIGNATURE)).append("\r\n");
        return mime.toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void base64OfCleanContentIsNotFlagged() {
        byte[] blob = Base64.getMimeEncoder().encode("nothing to see here, just a long enough clean payload".getBytes(StandardCharsets.US_ASCII));

        assertEquals(Result.CLEAN, SesContentScan.scan(blob));
    }

    @Test
    void cleanMimeWithAnAttachmentIsNotFlagged() {
        String encoded = Base64.getEncoder().encodeToString("just a file".getBytes(StandardCharsets.US_ASCII));
        String mime = "From: a@example.com\r\nTo: b@example.com\r\nSubject: x\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n"
                + "--b\r\nContent-Type: text/plain\r\n\r\nhello\r\n"
                + "--b\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + encoded + "\r\n--b--\r\n";

        assertEquals(Result.CLEAN, SesContentScan.scan(mime.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bytesThatAreNotMimeFallBackToARawScan() {
        byte[] signature = SIGNATURE.getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[signature.length + 2];
        bytes[0] = 0;
        bytes[1] = 1;
        System.arraycopy(signature, 0, bytes, 2, signature.length);

        assertEquals(Result.REJECTED, SesContentScan.scan(bytes));
    }

    @Test
    void emptyInputIsNotFlagged() {
        assertEquals(Result.CLEAN, SesContentScan.scan(new byte[0]));
        assertEquals(Result.CLEAN, SesContentScan.scan((byte[]) null));
    }

    // Escapes the characters of the signature that quoted-printable may encode, and soft-wraps,
    // so the wire carries no plain copy of the signature.
    private static String quotedPrintable(String decoded) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < decoded.length(); i += 60) {
            if (i > 0) {
                out.append("=\r\n");
            }
            out.append(decoded.substring(i, Math.min(decoded.length(), i + 60))
                    .replace("!", "=21").replace("$", "=24"));
        }
        return out.toString();
    }
}
