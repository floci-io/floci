package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #2958: a published version stored a reference to {@code $LATEST}'s code directory rather
 * than a copy of the code, and extraction replaces that directory wholesale on every deploy. So a
 * later UpdateFunctionCode rewrote what an already-published version would run, leaving the version
 * advertising one CodeSha256 over a different build.
 */
class LambdaVersionCodeIsolationTest {

    private static final String REGION = "us-east-1";

    private LambdaService service;

    @BeforeEach
    void setUp() {
        service = new LambdaService(
                new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>()),
                new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-version-isolation")),
                new ZipExtractor(),
                new RegionResolver(REGION, "000000000000"));
    }

    private static String zipB64(String body) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("handler.py"));
            zos.write((body + "\n").getBytes("UTF-8"));
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static Map<String, Object> createRequest(String name, String body) throws Exception {
        return new HashMap<>(Map.of(
                "FunctionName", name,
                "Runtime", "python3.12",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "handler.handler",
                "Code", new HashMap<>(Map.of("ZipFile", zipB64(body)))));
    }

    @Test
    void aPublishedVersionKeepsItsOwnCodeWhenLatestIsRedeployed() throws Exception {
        service.createFunction(REGION, createRequest("isolation-fn", "v1"));

        LambdaFunction v1 = service.publishVersion(REGION, "isolation-fn", null);
        Path v1Path = Path.of(v1.getCodeLocalPath());
        String v1Sha = v1.getCodeSha256();

        assertTrue(Files.isDirectory(v1Path), "a published version must have its own code directory");
        assertEquals("v1", Files.readString(v1Path.resolve("handler.py")).trim());

        // Redeploy $LATEST. Extraction replaces its directory wholesale, which is what used to take
        // the published version's code with it.
        service.updateFunctionCode(REGION, "isolation-fn",
                new HashMap<>(Map.of("ZipFile", zipB64("v2"))));

        LambdaFunction latest = service.getFunction(REGION, "isolation-fn");
        assertNotEquals(v1Path.toString(), latest.getCodeLocalPath(),
                "a version must not share $LATEST's directory");
        assertEquals("v2",
                Files.readString(Path.of(latest.getCodeLocalPath()).resolve("handler.py")).trim());

        // The version still holds the bytes it was published from, and they still match the hash it
        // advertises, which is the guarantee that was broken.
        assertTrue(Files.isDirectory(v1Path), "the version's code must survive a redeploy of $LATEST");
        assertEquals("v1", Files.readString(v1Path.resolve("handler.py")).trim());
        assertEquals(v1Sha, v1.getCodeSha256());
    }

    @Test
    void deletingAFunctionRemovesItsVersionsCode() throws Exception {
        service.createFunction(REGION, createRequest("isolation-del-fn", "v1"));
        LambdaFunction v1 = service.publishVersion(REGION, "isolation-del-fn", null);
        Path v1Path = Path.of(v1.getCodeLocalPath());
        assertTrue(Files.isDirectory(v1Path));

        service.deleteFunction(REGION, "isolation-del-fn");

        assertFalse(Files.exists(v1Path),
                "a version's code directory must not outlive the function it belongs to");
    }
}
