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
        assertEquals(version.integrity(), envelope.attachmentIntegrity().get(filename));
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
                envelope.attachmentIntegrity().get("a.tgz"));
    }

    @Test
    void anAttachmentLargerThanTheStringLimitStillStreamsThroughTheDigest() throws Exception {
        byte[] tarball = new byte[30 * 1024 * 1024];
        new Random(9).nextBytes(tarball);
        String json = "{\"_attachments\":{\"big.tgz\":{\"data\":\"" + Base64.getEncoder().encodeToString(tarball)
                + "\"}}}";

        NpmPublishEnvelopeScanner.Envelope envelope = scan(json);

        assertEquals("sha512-" + Base64.getEncoder().encodeToString(sha512(tarball)),
                envelope.attachmentIntegrity().get("big.tgz"));
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

    @Test
    void attachmentDataContainingAnEscapeIsRejected() {
        assertInvalid("{\"_attachments\":{\"a.tgz\":{\"data\":\"ab\\/cd\"}}}");
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
