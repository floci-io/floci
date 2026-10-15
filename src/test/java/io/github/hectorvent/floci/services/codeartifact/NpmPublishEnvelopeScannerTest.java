package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpmPublishEnvelopeScannerTest {

    @Test
    void theRealPublishFixtureHashesToItsOwnDistIntegrity() throws Exception {
        Path fixture = Path.of("src/test/resources/codeartifact/npm-publish-fixture.json");
        JsonNode raw = new ObjectMapper().readTree(Files.readAllBytes(fixture));

        NpmPublishEnvelopeScanner.Envelope envelope = NpmPublishEnvelopeScanner.scan(Files.newInputStream(fixture));

        assertEquals("floci-verdaccio-spike", envelope.name());
        assertEquals(Map.of("latest", "1.0.0"), envelope.distTags());
        NpmPublishEnvelopeScanner.Version version = envelope.versions().get("1.0.0");
        assertEquals(raw.at("/versions/1.0.0/dist/integrity").asText(), version.integrity());
        String filename = raw.at("/versions/1.0.0/dist/tarball").asText().replaceAll(".*/", "");
        assertEquals(version.integrity(), envelope.attachments().get(filename).integrity());
    }

    @Test
    void escapesInTrackedValuesAreDecoded() throws Exception {
        String json = "{\"name\":\"my\\u002dpkg\",\"versions\":{}}";

        NpmPublishEnvelopeScanner.Envelope envelope = scan(json);

        assertEquals("my-pkg", envelope.name());
    }

    @Test
    void attachmentDataIsHashedAcrossAStreamOfAnyLength() throws Exception {
        byte[] tarball = new byte[3 * 1024 * 1024 + 1];
        new Random(7).nextBytes(tarball);
        String json = "{\"_attachments\":{\"a.tgz\":{\"data\":\"" + Base64.getEncoder().encodeToString(tarball)
                + "\"}}}";

        NpmPublishEnvelopeScanner.Envelope envelope = scan(json);

        assertEquals("sha512-" + Base64.getEncoder().encodeToString(sha512(tarball)),
                envelope.attachments().get("a.tgz").integrity());
        assertEquals(tarball.length, envelope.attachments().get("a.tgz").size());
    }

    @Test
    void anAttachmentLargerThanTheStringLimitStillStreamsThroughTheDigest() throws Exception {
        byte[] tarball = new byte[30 * 1024 * 1024];
        new Random(9).nextBytes(tarball);
        String json = "{\"_attachments\":{\"big.tgz\":{\"data\":\"" + Base64.getEncoder().encodeToString(tarball)
                + "\"}}}";

        NpmPublishEnvelopeScanner.Envelope envelope = scan(json);

        assertEquals("sha512-" + Base64.getEncoder().encodeToString(sha512(tarball)),
                envelope.attachments().get("big.tgz").integrity());
        assertEquals(tarball.length, envelope.attachments().get("big.tgz").size());
    }

    @Test
    void anUntrackedStringOfAnySizeIsSkippedAndTheTrackedFieldsAfterItStillRead() throws Exception {
        String huge = "a".repeat(25 * 1024 * 1024);

        NpmPublishEnvelopeScanner.Envelope envelope = scan("{\"readme\":\"" + huge + "\",\"name\":\"a\"}");

        assertEquals("a", envelope.name());
    }

    @Test
    void aTrackedStringBeyondTheLimitIsRejected() {
        assertInvalid("{\"name\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}");
    }

    @Test
    void untrackedStringsAreSkippedWithoutBeingKept() throws Exception {
        String json = "{\"readme\":\"anything \\\" at all\",\"versions\":{}}";

        NpmPublishEnvelopeScanner.Envelope envelope = scan(json);

        assertNull(envelope.name());
        assertEquals(Map.of(), envelope.versions());
    }

    @Test
    void duplicateKeysAreRejected() {
        assertInvalid("{\"name\":\"a\",\"name\":\"b\"}");
    }

    @Test
    void base64WithAnInvalidCharacterIsRejected() {
        assertInvalid("{\"_attachments\":{\"a.tgz\":{\"data\":\"ab!d\"}}}");
    }

    @Test
    void base64WhoseLengthIsNotAMultipleOfFourIsRejected() {
        assertInvalid("{\"_attachments\":{\"a.tgz\":{\"data\":\"abcde\"}}}");
    }

    /**
     * A real finding turned out not to be one: a review raised whether a JSON-escaped attachment
     * (most plausibly {@code \/}, the one base64 character JSON permits escaping, though no real
     * npm client's own {@code JSON.stringify} ever actually produces one) would be silently
     * undercounted here. Checked directly rather than assumed either way: {@code
     * JsonParser#readBinaryValue} already resolves the escape before decoding, so a validly
     * escaped, valid-length attachment decodes correctly, digest and byte count both exactly
     * right. {@code 0xFF 0xFF 0xFF} is a deliberately chosen fixture: its base64 encoding is
     * exactly {@code "////"}, four real {@code /} characters with no padding, so every character
     * of the escaped payload below actually exercises the escape-resolution path, not just one
     * character buried among ordinary ones.
     */
    @Test
    void attachmentDataContainingAnEscapedSlashIsDecodedCorrectly() throws Exception {
        byte[] tarball = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        String base64 = Base64.getEncoder().encodeToString(tarball);
        assertEquals("////", base64);
        String escaped = base64.replace("/", "\\/");
        String json = "{\"_attachments\":{\"a.tgz\":{\"data\":\"" + escaped + "\"}}}";

        NpmPublishEnvelopeScanner.Envelope envelope = scan(json);

        assertEquals("sha512-" + Base64.getEncoder().encodeToString(sha512(tarball)),
                envelope.attachments().get("a.tgz").integrity());
        assertEquals(tarball.length, envelope.attachments().get("a.tgz").size());
    }

    /**
     * An escape sequence present is not, on its own, a pass: decoded, this is {@code "a/bcd"},
     * 5 characters, not a multiple of 4, so still genuinely invalid base64 regardless of the
     * escape. (This is also what the older, differently-reasoned version of this test actually
     * exercised: {@code "ab\/cd"} decodes to {@code "ab/cd"}, 5 characters, rejected for the same
     * length reason, not because it contained an escape at all.)
     */
    @Test
    void attachmentDataContainingAnEscapeButStillInvalidBase64IsRejected() {
        assertInvalid("{\"_attachments\":{\"a.tgz\":{\"data\":\"a\\/bcd\"}}}");
    }

    @Test
    void trailingGarbageAfterTheEnvelopeIsRejected() {
        assertInvalid("{\"name\":\"a\"} extra");
    }

    @Test
    void anUnrecognizedLiteralIsRejected() {
        assertInvalid("{\"readme\":tru}");
    }

    @Test
    void nestingDeeperThanTheLimitIsRejected() {
        String json = "{\"x\":" + "[".repeat(40) + "]".repeat(40) + "}";

        assertInvalid(json);
    }

    @Test
    void aNonStringDistTagIsRejectedRatherThanDropped() {
        assertInvalid("{\"dist-tags\":{\"latest\":1}}");
    }

    @Test
    void aNonStringNameIsRejected() {
        assertInvalid("{\"name\":null}");
    }

    @Test
    void aValidNumberLongerThanAnyFixedLimitIsAccepted() throws Exception {
        String digits = "1".repeat(100_000);

        NpmPublishEnvelopeScanner.Envelope envelope = scan("{\"readme\":" + digits + ".5e-10,\"name\":\"a\"}");

        assertEquals("a", envelope.name());
    }

    @Test
    void aMalformedNumberIsRejected() {
        assertInvalid("{\"readme\":1.}");
        assertInvalid("{\"readme\":01}");
        assertInvalid("{\"readme\":-}");
    }

    @Test
    void aNonObjectEnvelopeIsRejected() {
        assertInvalid("[1,2,3]");
    }

    /**
     * Found by Qodo review: before this field was read at all, any shape here fell to the default
     * branch's plain {@code skipChildren()}; once it became a tracked field (for readme capture),
     * a value sent as anything but a string (a real client never would, but a hand-built or buggy
     * one might) must keep that same tolerance, not reject the whole envelope over one best-effort
     * field. {@code dist.integrity} on the same version, a field this scanner does require, proves
     * the rest of the version is still read correctly when {@code readme} is skipped.
     */
    @Test
    void aNonStringPerVersionReadmeIsToleratedRatherThanRejectingTheWholeEnvelope() throws Exception {
        String json = "{\"versions\":{\"1.0.0\":{\"readme\":null,\"dist\":{\"tarball\":\"x/y-1.0.0.tgz\","
                + "\"integrity\":\"sha512-abc\"}}}}";

        NpmPublishEnvelopeScanner.Envelope envelope = scan(json);

        NpmPublishEnvelopeScanner.Version version = envelope.versions().get("1.0.0");
        assertNull(version.readme());
        assertEquals("sha512-abc", version.integrity());
    }

    @Test
    void anObjectShapedPerVersionReadmeIsToleratedTheSameWay() throws Exception {
        String json = "{\"versions\":{\"1.0.0\":{\"readme\":{\"not\":\"a string\"},\"dist\":{\"tarball\":"
                + "\"x/y-1.0.0.tgz\",\"integrity\":\"sha512-abc\"}}}}";

        NpmPublishEnvelopeScanner.Envelope envelope = scan(json);

        assertNull(envelope.versions().get("1.0.0").readme());
    }

    private static NpmPublishEnvelopeScanner.Envelope scan(String json) throws IOException {
        InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return NpmPublishEnvelopeScanner.scan(in);
    }

    private static void assertInvalid(String json) {
        assertThrows(IOException.class, () -> scan(json));
    }

    private static byte[] sha512(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(bytes);
    }
}
