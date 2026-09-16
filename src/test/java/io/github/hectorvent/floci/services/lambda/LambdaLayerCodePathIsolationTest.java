package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.mockito.Answers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaLayerCodePathIsolationTest {

    private static final String LAYER_NAME = "shared-layer";

    @Test
    void sameLayerVersionInDifferentRegionsUsesDifferentCodePaths(@TempDir Path baseDir) throws Exception {
        LambdaLayerStore store = new LambdaLayerStore(AccountAwareStorageBackend.inMemory("111111111111"));
        LambdaLayerService service = serviceFor(baseDir, "111111111111", store);

        LambdaLayerVersion east = service.publishLayerVersion("us-east-1", LAYER_NAME, request("east"));
        LambdaLayerVersion west = service.publishLayerVersion("eu-west-1", LAYER_NAME, request("west"));

        assertNotEquals(east.getCodeLocalPath(), west.getCodeLocalPath());
        assertEqualsContent(east.getCodeLocalPath(), "east");
        assertEqualsContent(west.getCodeLocalPath(), "west");
    }

    @Test
    void sameLayerVersionInDifferentAccountsUsesDifferentCodePaths(@TempDir Path baseDir) throws Exception {
        LambdaLayerVersion accountA = publish(serviceFor(baseDir, "111111111111"), "A");
        LambdaLayerVersion accountB = publish(serviceFor(baseDir, "222222222222"), "B");

        assertNotEquals(accountA.getCodeLocalPath(), accountB.getCodeLocalPath());
        assertEqualsContent(accountA.getCodeLocalPath(), "A");
        assertEqualsContent(accountB.getCodeLocalPath(), "B");
    }

    @Test
    void dotSegmentsInAccountAndRegionCannotEscapeTheirDirectories(@TempDir Path baseDir) throws Exception {
        LambdaLayerStore store = new LambdaLayerStore(AccountAwareStorageBackend.inMemory(".."));
        LambdaLayerVersion layer = serviceFor(baseDir, "..", store)
                .publishLayerVersion("..", "../" + LAYER_NAME, request("dots"));

        Path layerRoot = baseDir.resolve("layers").toAbsolutePath().normalize();
        Path extracted = Path.of(layer.getCodeLocalPath()).toAbsolutePath().normalize();
        assertTrue(extracted.startsWith(layerRoot), "extracted path escaped layer root: " + extracted);
        Path relative = layerRoot.relativize(extracted);
        assertEquals(4, relative.getNameCount(), "expected layers/<account>/<region>/<layer>/<version>: " + relative);
        relative.forEach(segment -> assertNotEquals("..", segment.toString(), "path segment escaped: " + relative));
        assertEqualsContent(layer.getCodeLocalPath(), "dots");
    }

    @Test
    void allDotPathSegmentsUseTheSameSafePlaceholderAsCodeStore(@TempDir Path baseDir) throws Exception {
        LambdaLayerVersion layer = serviceFor(baseDir, "...")
                .publishLayerVersion("...", "...", request("dots"));

        assertEquals(baseDir.resolve("layers/_/_/_/1").toAbsolutePath().normalize(),
                Path.of(layer.getCodeLocalPath()).toAbsolutePath().normalize());
    }

    @Test
    void deletingLayerVersionInOneRegionKeepsTheOtherRegionsCode(@TempDir Path baseDir) throws Exception {
        LambdaLayerStore store = new LambdaLayerStore(AccountAwareStorageBackend.inMemory("111111111111"));
        LambdaLayerService service = serviceFor(baseDir, "111111111111", store);
        LambdaLayerVersion east = service.publishLayerVersion("us-east-1", LAYER_NAME, request("east"));
        LambdaLayerVersion west = service.publishLayerVersion("eu-west-1", LAYER_NAME, request("west"));

        service.deleteLayerVersion("us-east-1", LAYER_NAME, east.getVersion());

        assertFalse(Files.exists(Path.of(east.getCodeLocalPath())));
        assertEqualsContent(west.getCodeLocalPath(), "west");
    }

    @Test
    void deletingLayerVersionInOneAccountKeepsTheOtherAccountsCode(@TempDir Path baseDir) throws Exception {
        LambdaLayerService serviceA = serviceFor(baseDir, "111111111111");
        LambdaLayerService serviceB = serviceFor(baseDir, "222222222222");
        LambdaLayerVersion accountA = publish(serviceA, "A");
        LambdaLayerVersion accountB = publish(serviceB, "B");

        serviceA.deleteLayerVersion("us-east-1", LAYER_NAME, accountA.getVersion());

        assertFalse(Files.exists(Path.of(accountA.getCodeLocalPath())));
        assertEqualsContent(accountB.getCodeLocalPath(), "B");
    }

    @Test
    void restartedServiceUsesThePersistedCodePath(@TempDir Path baseDir) throws Exception {
        LambdaLayerStore store = new LambdaLayerStore(AccountAwareStorageBackend.inMemory("111111111111"));
        LambdaLayerVersion published = publish(serviceFor(baseDir, "111111111111", store), "persisted");

        LambdaLayerVersion restored = serviceFor(baseDir, "111111111111", store)
                .getLayerVersion("us-east-1", LAYER_NAME, published.getVersion());

        assertEquals(published.getCodeLocalPath(), restored.getCodeLocalPath());
        assertEqualsContent(restored.getCodeLocalPath(), "persisted");
    }

    private static LambdaLayerVersion publish(LambdaLayerService service, String content) throws Exception {
        return service.publishLayerVersion("us-east-1", LAYER_NAME, request(content));
    }

    private static LambdaLayerService serviceFor(Path baseDir, String account) {
        return serviceFor(baseDir, account, new LambdaLayerStore(AccountAwareStorageBackend.inMemory(account)));
    }

    private static LambdaLayerService serviceFor(Path baseDir, String account, LambdaLayerStore store) {
        EmulatorConfig config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().lambda().codePath()).thenReturn(baseDir.toString());
        when(config.services().lambda().zipMaxEntries()).thenReturn(100_000);
        when(config.services().lambda().executor()).thenReturn("docker");
        return new LambdaLayerService(store, new ZipExtractor(), config,
                new RegionResolver("us-east-1", account), null);
    }

    private static Map<String, Object> request(String content) throws Exception {
        return Map.of("Content", Map.of("ZipFile", zipBase64(content)));
    }

    private static String zipBase64(String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(content.getBytes());
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static void assertEqualsContent(String path, String expected) throws Exception {
        assertEquals(expected, Files.readString(Path.of(path).resolve("index.js")));
    }
}
