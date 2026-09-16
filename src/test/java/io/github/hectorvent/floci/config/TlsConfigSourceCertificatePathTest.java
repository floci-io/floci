package io.github.hectorvent.floci.config;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the certificate path against the backslash-mangling that broke TLS startup on Windows
 * in the sibling Azure emulator (floci-az #142), where a path such as
 * {@code D:\Dev\floci\data\tls\cert.crt} reached the HTTP server as
 * {@code D:Devflocidatatlscert.crt} and startup died with {@code NoSuchFileException}.
 *
 * <p>The mangling is not general SmallRye behaviour. Expression handling leaves a value with no
 * {@code $} untouched, and it deliberately compiles without escape processing. The damage comes
 * from {@code StringUtil.split}, which is reached only by the converter for collection-typed
 * properties, and which consumes each backslash.
 *
 * <p>That makes the property's declared type the whole defence. floci-aws is safe today only
 * because it writes {@code quarkus.tls.key-store.pem.0.cert}, a scalar {@code Path}. The key it
 * used to write, {@code quarkus.http.ssl.certificate.files}, is an {@code Optional<List<Path>>}
 * and is mangled. Nothing else records that distinction, so switching back would silently
 * reintroduce the bug; these tests fail if that happens.
 */
class TlsConfigSourceCertificatePathTest {

    /** A Windows path with no {@code $}, the shape that broke floci-az. */
    private static final String WINDOWS_PATH = "D:\\Dev\\floci\\data\\tls\\floci-server.crt";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.storage.persistent-path");
    }

    private static SmallRyeConfig config(String key, String value) {
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(Map.of(key, value), "test", 300))
                .build();
    }

    @Test
    void aWindowsPathSurvivesAsAScalarPathProperty() {
        SmallRyeConfig config = config("quarkus.tls.key-store.pem.0.cert", WINDOWS_PATH);

        assertEquals(WINDOWS_PATH, config.getValue("quarkus.tls.key-store.pem.0.cert", String.class),
                "a scalar property must round-trip a Windows path unchanged");
        assertEquals(Path.of(WINDOWS_PATH),
                config.getValue("quarkus.tls.key-store.pem.0.cert", Path.class),
                "converting to Path must not lose the separators either");
    }

    @Test
    void theSamePathIsMangledWhenReadAsACollection() {
        // Not a wish, a demonstration: this is exactly what floci-az hit, and it is why the
        // property's type is what protects us rather than anything in our own code.
        SmallRyeConfig config = config("quarkus.tls.key-store.pem.0.cert", WINDOWS_PATH);

        List<String> asList = config.getValues("quarkus.tls.key-store.pem.0.cert", String.class);

        assertEquals(1, asList.size());
        assertNotEquals(WINDOWS_PATH, asList.getFirst(),
                "if this ever stops mangling, the collection converter changed and the guard below "
                        + "can be relaxed");
        assertEquals("D:Devflocidatatlsfloci-server.crt", asList.getFirst(),
                "the collection converter consumes every backslash");
    }

    @Test
    void theEmittedCertificateKeysAreScalarNotCollectionTyped() {
        // The actual regression guard. Quarkus types `...pem.0.cert` as a scalar Path and
        // `quarkus.http.ssl.certificate.files` as Optional<List<Path>>, so emitting the latter
        // would put the certificate path back through the splitter that broke floci-az.
        TlsConfigSource source = new TlsConfigSource();

        String certKey = "quarkus.tls.key-store.pem.0.cert";
        String keyKey = "quarkus.tls.key-store.pem.0.key";

        assertNotNull(source.getValue(certKey), "the scalar PEM cert key must be the one emitted");
        assertNotNull(source.getValue(keyKey), "the scalar PEM key key must be the one emitted");

        for (String plural : List.of("quarkus.http.ssl.certificate.files",
                "quarkus.http.ssl.certificate.key-files")) {
            assertTrue(source.getValue(plural) == null,
                    "must not emit " + plural + ": it is collection-typed, so a Windows path "
                            + "would be mangled by the collection converter");
        }
    }

    @Test
    void theGeneratedPathIsAbsoluteAndUsableAsWritten() {
        TlsConfigSource source = new TlsConfigSource();

        String certPath = source.getValue("quarkus.tls.key-store.pem.0.cert");

        assertNotNull(certPath);
        assertTrue(Path.of(certPath).isAbsolute(), "the emitted path must be absolute: " + certPath);
        assertTrue(java.nio.file.Files.exists(Path.of(certPath)),
                "the emitted path must resolve to the generated certificate: " + certPath);
    }
}
