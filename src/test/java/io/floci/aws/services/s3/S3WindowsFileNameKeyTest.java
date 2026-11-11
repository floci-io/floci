package io.floci.aws.services.s3;

import io.floci.aws.core.common.Digests;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Object keys whose segments Windows cannot hold as file names (a colon from a timestamp, a
 * backslash, a trailing dot or space, a device name such as NUL), in disk-storage mode. S3
 * accepts all of them, so the body file gets an encoded name there while the object store keeps
 * the key.
 */
class S3WindowsFileNameKeyTest {

    private static final String DATA_SUFFIX = ".s3data";

    @TempDir
    Path tempDir;

    private S3Service s3Service;
    private Path dataRoot;

    @BeforeEach
    void setUp() {
        dataRoot = tempDir.resolve("s3");
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);
        s3Service.createBucket("bucket", "us-east-1");
    }

    @Test
    void keysWithCharactersWindowsRejectsRoundTrip() {
        assertKeysRoundTrip(List.of(
                "logs/2026-11-05T10:00:00Z.json",
                "a<b>c|d?e*f\"g",
                "tab\there",
                "trailing-dot./x",
                "trailing-space /x"));
    }

    @Test
    void keysNamingAWindowsDeviceRoundTrip() {
        assertKeysRoundTrip(List.of(
                "CON",
                "NUL.txt",
                "PRN.log",
                "COM1",
                "com¹",
                "NUL/report.json",
                "LPT²/x",
                "lpt³.log",
                "Aux .tar.gz"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nul", "NUL.txt", "COM¹.log", "LPT²/x", "Aux .tar.gz"})
    void aVersionedKeyNamingAWindowsDeviceKeepsEachVersion(String key) {
        s3Service.putBucketVersioning("bucket", "Enabled");
        String firstVersion = s3Service.putObject("bucket", key, bytes("first"), "text/plain", Map.of())
                .getVersionId();
        s3Service.putObject("bucket", key, bytes("second"), "text/plain", Map.of());

        assertArrayEquals(bytes("second"), s3Service.getObject("bucket", key).getData());
        assertArrayEquals(bytes("first"), s3Service.getObject("bucket", key, firstVersion).getData());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void everyStoredNameIsOneWindowsHoldsAsWritten() throws IOException {
        for (String key : List.of("NUL.txt", "con.tar.gz/x", "AUX .txt", "COM¹.log", "logs/10:00:00Z.json")) {
            s3Service.putObject("bucket", key, bytes(key), "text/plain", Map.of());
        }

        assertTrue(Files.exists(objectFile("NUL_txt~" + Digests.sha256Hex("NUL.txt"))),
                "NUL.txt is stored under a prefix that no longer reads as NUL");
        try (Stream<Path> paths = Files.walk(dataRoot)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                assertTrue(S3Service.isWindowsFileName(name), "a name Windows does not hold as written: " + name);
            }
        }
    }

    @Test
    void keysWindowsWouldFoldOntoOneFileStayDistinct() {
        s3Service.putObject("bucket", "dir/file", bytes("slash"), "text/plain", Map.of());
        s3Service.putObject("bucket", "dir\\file", bytes("backslash"), "text/plain", Map.of());
        s3Service.putObject("bucket", "dir./file", bytes("trailing dot"), "text/plain", Map.of());

        assertArrayEquals(bytes("slash"), s3Service.getObject("bucket", "dir/file").getData());
        assertArrayEquals(bytes("backslash"), s3Service.getObject("bucket", "dir\\file").getData());
        assertArrayEquals(bytes("trailing dot"), s3Service.getObject("bucket", "dir./file").getData());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @EnabledOnOs(OS.WINDOWS)
    void aRawUppercaseDigestNameCannotOverwriteAnEncodedKey(boolean invalidFirst) {
        String invalidKey = "x:";
        String craftedKey = "x_~" + Digests.sha256Hex(invalidKey).toUpperCase(Locale.ROOT);
        List<String> keys = invalidFirst ? List.of(invalidKey, craftedKey) : List.of(craftedKey, invalidKey);
        for (String key : keys) {
            s3Service.putObject("bucket", key, bytes("body of " + key), "text/plain", Map.of());
        }

        for (String key : keys) {
            assertArrayEquals(bytes("body of " + key), s3Service.getObject("bucket", key).getData(), key);
        }
    }

    @Test
    void aLastSegmentEndingInADotOrSpaceKeepsItsUnencodedPath() {
        for (String key : List.of("logs/report.", "logs/report ")) {
            s3Service.putObject("bucket", key, bytes(key), "text/plain", Map.of());

            assertTrue(Files.exists(objectFile(key)),
                    "with .s3data appended Windows holds the name, so it stays where earlier versions put it: " + key);
            assertArrayEquals(bytes(key), s3Service.getObject("bucket", key).getData(), key);
        }
    }

    @Test
    void aLegacyLayoutObjectWhoseLastSegmentEndsInADotOrSpaceIsStillCopiedIn() throws IOException {
        for (String key : List.of("logs/report.", "logs/report ")) {
            byte[] body = bytes("body of " + key);
            s3Service.putObject("bucket", key, body, "text/plain", Map.of());
            Path legacy = dataRoot.resolve("bucket").resolve(key + DATA_SUFFIX);
            Files.createDirectories(legacy.getParent());
            Files.move(objectFile(key), legacy);

            assertArrayEquals(body, s3Service.getObject("bucket", key).getData(), key);
            assertArrayEquals(body, Files.readAllBytes(objectFile(key)), "copied account-scoped body: " + key);
            assertArrayEquals(body, Files.readAllBytes(legacy), "preserved legacy body: " + key);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"logs/report.", "logs/report "})
    void aVersionedKeyEndingInADotOrSpaceKeepsItsOwnNullVersion(String key) {
        s3Service.putObject("bucket", "logs/report", bytes("plain"), "text/plain", Map.of());
        s3Service.putObject("bucket", key, bytes("trailing character"), "text/plain", Map.of());
        s3Service.putBucketVersioning("bucket", "Enabled");
        s3Service.putObject("bucket", "logs/report", bytes("plain, versioned"), "text/plain", Map.of());
        s3Service.putObject("bucket", key, bytes("trailing character, versioned"), "text/plain", Map.of());

        assertArrayEquals(bytes("plain"), s3Service.getObject("bucket", "logs/report", "null").getData());
        assertArrayEquals(bytes("trailing character"), s3Service.getObject("bucket", key, "null").getData());
        assertArrayEquals(bytes("plain, versioned"), s3Service.getObject("bucket", "logs/report").getData());
        assertArrayEquals(bytes("trailing character, versioned"), s3Service.getObject("bucket", key).getData());
    }

    @Test
    void windowsFileNameRulesMatchTheFileSystem() {
        assertTrue(S3Service.isWindowsFileName("report.json"));
        assertTrue(S3Service.isWindowsFileName("."));
        assertTrue(S3Service.isWindowsFileName(".."));
        assertTrue(S3Service.isWindowsFileName(""));
        assertTrue(S3Service.isWindowsFileName("report..s3data"));
        assertTrue(S3Service.isWindowsFileName("report .s3data"));

        assertFalse(S3Service.isWindowsFileName("10:00:00Z"));
        assertFalse(S3Service.isWindowsFileName("a\\b"));
        assertFalse(S3Service.isWindowsFileName("q?"));
        assertFalse(S3Service.isWindowsFileName("bell\u0007"));
        assertFalse(S3Service.isWindowsFileName("dir."));
        assertFalse(S3Service.isWindowsFileName("dir "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CON", "con", "NUL.txt", "nul.tar.gz", "AUX .txt", "PRN.s3data",
            "COM1", "lpt9", "COM¹", "com¹", "LPT³.log"})
    void aWindowsDeviceNameIsNotAFileNameWithOrWithoutAnExtension(String name) {
        assertFalse(S3Service.isWindowsFileName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONSOLE", "NULL.txt", "COM10", "LPT", "report.con"})
    void aNameThatOnlyLooksLikeADeviceIsAFileName(String name) {
        assertTrue(S3Service.isWindowsFileName(name));
    }

    @ParameterizedTest
    @CsvSource({"NUL.txt, NUL_txt", "'Aux .tar.gz', 'Aux _tar.gz'", "COM¹.log, COM¹_log",
            "a:b.json, a_b.json", "NULL.txt, NULL.txt", "nul, nul"})
    void anEncodedPrefixDropsTheDotAfterADeviceName(String prefix, String safePrefix) {
        assertEquals(safePrefix, S3Service.windowsSafePrefix(prefix));
    }

    private void assertKeysRoundTrip(List<String> keys) {
        for (String key : keys) {
            byte[] body = bytes("body of " + key);
            s3Service.putObject("bucket", key, body, "text/plain", Map.of());
            assertArrayEquals(body, s3Service.getObject("bucket", key).getData(), key);
        }

        List<String> listed = s3Service.listObjects("bucket", null, null, 1000)
                .stream().map(S3Object::getKey).sorted().toList();
        assertEquals(keys.stream().sorted().toList(), listed);
    }

    private Path objectFile(String storedPath) {
        return dataRoot.resolve(".accounts").resolve("000000000000").resolve("bucket")
                .resolve(storedPath + DATA_SUFFIX);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
