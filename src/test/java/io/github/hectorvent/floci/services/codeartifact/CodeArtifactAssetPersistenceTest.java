package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageVersionAssetResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PublishPackageVersionResult;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackageVersion;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Asset bytes must not ride along in the JSON-backed {@code codeartifact-package-versions.json}
 * store: that file is rewritten whole on every publish, so embedding a growing set of assets
 * there would make every later publish pay to re-serialize every earlier one. This exercises
 * {@link CodeArtifactService} against a real {@link PersistentStorage}, the same backend
 * {@code persistent} storage mode uses in production.
 */
class CodeArtifactAssetPersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";

    @Test
    void assetBytesAreNotEmbeddedInThePersistedPackageVersionJson(@TempDir Path dir) throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        byte[] content = "hello world, this is the asset payload".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "false", content);

        String json = Files.readString(dir.resolve("codeartifact-package-versions.json"));
        String base64Content = Base64.getEncoder().encodeToString(content);
        assertFalse(json.contains(base64Content),
                "persisted package-version JSON must not embed asset bytes: " + json);
    }

    @Test
    void assetContentSurvivesRestart(@TempDir Path dir) {
        CodeArtifactService first = newService(dir);
        first.createDomain(REGION, "dom", null, Map.of());
        first.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult published = first.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", sha256Hex(content), "false", content);

        CodeArtifactService restarted = newService(dir);
        PackageVersionAssetResult result = restarted.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);

        assertEquals("hello world", new String(result.asset().getContent(), StandardCharsets.UTF_8));
        assertEquals(published.packageVersion().getRevision(), result.packageVersionRevision());
    }

    /**
     * The in-memory {@code PackageAsset.content} field is {@code @JsonIgnore}, so a package
     * version reloaded from disk (a fresh {@code CodeArtifactService} instance here, standing in
     * for a Floci restart) never has it populated; this only passes if the idempotent-republish
     * check in {@code publishPackageVersion} reads the real bytes from the asset store the same
     * way {@code getPackageVersionAsset} does, not that field.
     */
    @Test
    void republishingAnUnfinishedAssetAfterARestartWithIdenticalContentSucceedsIdempotently(@TempDir Path dir) {
        CodeArtifactService first = newService(dir);
        first.createDomain(REGION, "dom", null, Map.of());
        first.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        first.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "true", content);

        CodeArtifactService restarted = newService(dir);
        PublishPackageVersionResult result = restarted.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", sha256Hex(content), "true", content);

        assertEquals("Unfinished", result.packageVersion().getStatus());
        assertEquals(1, result.packageVersion().getAssets().size());
    }

    @Test
    void republishingAnUnfinishedAssetAfterARestartWithDifferentContentConflicts(@TempDir Path dir) {
        CodeArtifactService first = newService(dir);
        first.createDomain(REGION, "dom", null, Map.of());
        first.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        first.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "true", content);

        CodeArtifactService restarted = newService(dir);
        byte[] different = "different bytes".getBytes(StandardCharsets.UTF_8);
        AwsException e = assertThrows(AwsException.class, () -> restarted.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "a.txt", sha256Hex(different), "true", different));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("a.txt", e.getExtendedData().get("resourceId"));
    }

    /**
     * A package version whose metadata still lists an asset but whose backing file is gone (a
     * partial restore, or anything else that touched the asset store without also touching the
     * metadata) must not turn a republish into a hard failure: that would block the one thing that
     * could actually repair it. Greptile caught this as a real regression risk in the overwrite
     * check above; this is the case that check must not treat as "the asset exists and must match".
     */
    @Test
    void republishingAnAssetWhoseBackingFileIsMissingRepairsItInsteadOfFailing(@TempDir Path dir) throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "true", content);

        Path assetRoot = dir.resolve("codeartifact-assets");
        try (Stream<Path> paths = Files.walk(assetRoot)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                Files.delete(p);
            }
        }

        byte[] repaired = "repaired content".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", sha256Hex(repaired), "true", repaired);

        assertEquals("Unfinished", result.packageVersion().getStatus());
        PackageVersionAssetResult fetched = service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);
        assertEquals("repaired content", new String(fetched.asset().getContent(), StandardCharsets.UTF_8));
    }

    /**
     * The overwrite check streams the existing file in fixed-size chunks rather than loading it
     * whole, specifically to avoid holding two full copies of a large asset in memory at once for a
     * same-content retry. These three cases exercise the chunk boundary directly: content spanning
     * several reads of the comparison buffer, a mismatch that only appears in the final chunk, and a
     * length mismatch that only becomes apparent once the shorter side is exhausted.
     */
    @Test
    void republishingALargeAssetSpanningMultipleBufferReadsWithIdenticalContentSucceeds(@TempDir Path dir) {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = deterministicBytes(20_000);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.bin",
                sha256Hex(content), "true", content);

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.bin", sha256Hex(content), "true", content);

        assertEquals("Unfinished", result.packageVersion().getStatus());
        assertEquals(1, result.packageVersion().getAssets().size());
    }

    @Test
    void republishingALargeAssetWithAMismatchInTheFinalChunkConflicts(@TempDir Path dir) {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] original = deterministicBytes(20_000);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.bin",
                sha256Hex(original), "true", original);

        byte[] almostSame = original.clone();
        almostSame[almostSame.length - 1] ^= 0xFF;

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "a.bin", sha256Hex(almostSame), "true", almostSame));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void republishingALargeAssetWithDifferentLengthConflicts(@TempDir Path dir) {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] original = deterministicBytes(20_000);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.bin",
                sha256Hex(original), "true", original);

        byte[] shorter = Arrays.copyOf(original, original.length - 1);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "a.bin", sha256Hex(shorter), "true", shorter));
        assertEquals("ConflictException", e.getErrorCode());
    }

    private static byte[] deterministicBytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    @Test
    void distinctAssetNamesNeverCollideOnDisk(@TempDir Path dir) {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String longToken = "p".repeat(255);
        List<String> names = List.of("b", "x/../b", ".", "..", "b/c", "/b", "b/");
        for (String name : names) {
            byte[] content = ("content of " + name).getBytes(StandardCharsets.UTF_8);
            service.publishPackageVersion(REGION, "dom", null, "repo", "generic", longToken, longToken, longToken,
                    name, sha256Hex(content), "true", content);
        }

        CodeArtifactService restarted = newService(dir);
        for (String name : names) {
            PackageVersionAssetResult result = restarted.getPackageVersionAsset(REGION, "dom", null, "repo",
                    "generic", longToken, longToken, longToken, name, null);
            assertEquals("content of " + name, new String(result.asset().getContent(), StandardCharsets.UTF_8));
        }
    }

    private CodeArtifactService newService(Path dir) {
        AccountAwareStorageBackend<CodeArtifactDomain> domainStore = accountAware(dir, "codeartifact-domains.json",
                new TypeReference<Map<String, CodeArtifactDomain>>() {});
        AccountAwareStorageBackend<CodeArtifactRepository> repoStore = accountAware(dir,
                "codeartifact-repositories.json", new TypeReference<Map<String, CodeArtifactRepository>>() {});
        AccountAwareStorageBackend<CodeArtifactPackageVersion> packageVersionStore = accountAware(dir,
                "codeartifact-package-versions.json", new TypeReference<Map<String, CodeArtifactPackageVersion>>() {});

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0, String.class) + ":" + invocation.getArgument(1, String.class)
                        + ":" + ACCOUNT_ID + ":" + invocation.getArgument(2, String.class));

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        return new CodeArtifactService(domainStore, repoStore, packageVersionStore, regionResolver, config,
                false, dir.resolve("codeartifact-assets"), new CodeArtifactSidecarRegistry(List.of()));
    }

    private <V> AccountAwareStorageBackend<V> accountAware(Path dir, String fileName, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(fileName), type);
        backend.load();
        return new AccountAwareStorageBackend<>(backend, null, ACCOUNT_ID);
    }

    private static String sha256Hex(byte[] content) {
        try {
            return io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator.sha256Hex(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
