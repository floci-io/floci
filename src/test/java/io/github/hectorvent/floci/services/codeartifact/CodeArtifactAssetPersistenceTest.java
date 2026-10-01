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
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
     * The overwrite check compares against an asset's persisted SHA-256 (in {@code hashes}), not
     * its bytes, so a backing file that's missing on disk (a partial restore, or anything else that
     * touched the asset store without also touching the metadata) has no special case at all: a
     * republish of the exact content that was already recorded still passes the hash comparison and
     * naturally rewrites the file as a side effect of the normal write path below.
     */
    @Test
    void republishingWithMatchingContentRewritesAMissingBackingFile(@TempDir Path dir) throws IOException {
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

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", sha256Hex(content), "true", content);

        assertEquals("Unfinished", result.packageVersion().getStatus());
        PackageVersionAssetResult fetched = service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);
        assertEquals("hello world", new String(fetched.asset().getContent(), StandardCharsets.UTF_8));
    }

    /**
     * Same repair guarantee as the Unfinished case above, but for a version that's already
     * Published: the idempotent-retry short-circuit there always rewrites the now-confirmed-correct
     * bytes rather than trusting that the file, if present, already has the right content.
     */
    @Test
    void republishingAnAlreadyPublishedAssetWithMatchingContentRewritesAMissingBackingFile(@TempDir Path dir)
            throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "false", content);

        Path assetRoot = dir.resolve("codeartifact-assets");
        try (Stream<Path> paths = Files.walk(assetRoot)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                Files.delete(p);
            }
        }

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", sha256Hex(content), "false", content);

        assertEquals("Published", result.packageVersion().getStatus());
        PackageVersionAssetResult fetched = service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);
        assertEquals("hello world", new String(fetched.asset().getContent(), StandardCharsets.UTF_8));
    }

    /**
     * A backing file that exists but whose bytes no longer match its recorded hash (disk-level
     * corruption, or anything else that touched the file directly without going through Floci) must
     * not survive a matching-content retry: an existence-only check would see the file is "there"
     * and skip repairing it, letting the corruption persist through every future retry forever.
     * Greptile caught this as a real regression risk in the presence-check this test replaces.
     */
    @Test
    void republishingWithMatchingContentRepairsACorruptedBackingFile(@TempDir Path dir) throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "true", content);

        Path assetRoot = dir.resolve("codeartifact-assets");
        try (Stream<Path> paths = Files.walk(assetRoot)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                Files.write(p, "corrupted on disk".getBytes(StandardCharsets.UTF_8));
            }
        }

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", sha256Hex(content), "true", content);

        assertEquals("Unfinished", result.packageVersion().getStatus());
        PackageVersionAssetResult fetched = service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);
        assertEquals("hello world", new String(fetched.asset().getContent(), StandardCharsets.UTF_8));
    }

    /**
     * A backing file that exists but can't be read at all (a permission problem, not the missing-
     * file case) must go through the same repair as a corrupted or missing one, not fail the whole
     * publish, and must actually log the warning this behavior exists to add: a test that only
     * checks the repair succeeded would still pass if {@code LOG.warnv} were deleted entirely, so
     * this captures the real log record and asserts on it directly, the same way
     * {@code PersistentPathValidatorTest} already does against the same {@code org.jboss.logging}
     * backend. Skipped when the test runner can read regardless of permission bits (e.g. running as
     * root), since removing read permission would not actually block the read there; the assumption
     * keeps that a visibly skipped test, not a silently vacuous pass.
     */
    @Test
    void republishingWithMatchingContentRepairsAnUnreadableBackingFileAndLogsAWarning(@TempDir Path dir)
            throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "true", content);

        Path assetFile;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            assetFile = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Assumptions.assumeTrue(assetFile.toFile().setReadable(false),
                "test filesystem must support removing read permission");
        Assumptions.assumeFalse(Files.isReadable(assetFile),
                "removing read permission must actually block reads (not running as root)");

        try {
            List<LogRecord> records = LogCapture.capture(CodeArtifactService.class, () ->
                    service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0",
                            "a.txt", sha256Hex(content), "true", content));

            assertTrue(records.stream().anyMatch(r -> r.getMessage() != null
                            && r.getMessage().contains("Could not verify")
                            && r.getThrown() instanceof AccessDeniedException
                            && r.getParameters() != null
                            && r.getParameters().length > 0
                            && assetFile.toString().equals(String.valueOf(r.getParameters()[0]))),
                    "expected a warning logging the AccessDeniedException with the real file path "
                            + "(not, say, the internal composite key) as its first parameter, got: " + records);

            assertEquals("Unfinished", service.describePackageVersion(REGION, "dom", null, "repo", "generic", null,
                    "my-pkg", "1.0.0").getStatus());
            PackageVersionAssetResult fetched = service.getPackageVersionAsset(REGION, "dom", null, "repo",
                    "generic", null, "my-pkg", "1.0.0", "a.txt", null);
            assertEquals("hello world", new String(fetched.asset().getContent(), StandardCharsets.UTF_8));
        } finally {
            assetFile.toFile().setReadable(true);
        }
    }

    /**
     * The overwhelmingly common case, an intact asset republished unchanged, must stay a true
     * no-op that needs no write capacity at all: Greptile caught that always rewriting (an earlier
     * version of the fix above) would make a retry fail on a store that has no free space left,
     * even though nothing on disk actually needed to change. {@code writeAssetContent} always
     * replaces the file through a temp-file-then-rename, so its identity (device/inode) changing
     * would prove a write happened; checking permission bits instead would be unreliable under a
     * root-run CI container, which ignores them.
     */
    @Test
    void republishingAnIntactUnfinishedAssetDoesNotRewriteTheBackingFile(@TempDir Path dir) throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "true", content);
        Path assetFile;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            assetFile = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Object fileKeyBefore = Files.readAttributes(assetFile, BasicFileAttributes.class).fileKey();
        assertNotNull(fileKeyBefore, "test filesystem must support file keys for this identity check to be meaningful");

        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "true", content);

        Object fileKeyAfter = Files.readAttributes(assetFile, BasicFileAttributes.class).fileKey();
        assertEquals(fileKeyBefore, fileKeyAfter, "an intact retry must not replace the backing file");
    }

    @Test
    void republishingAnIntactPublishedAssetDoesNotRewriteTheBackingFile(@TempDir Path dir) throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "false", content);
        Path assetFile;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            assetFile = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Object fileKeyBefore = Files.readAttributes(assetFile, BasicFileAttributes.class).fileKey();
        assertNotNull(fileKeyBefore, "test filesystem must support file keys for this identity check to be meaningful");

        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "false", content);

        Object fileKeyAfter = Files.readAttributes(assetFile, BasicFileAttributes.class).fileKey();
        assertEquals(fileKeyBefore, fileKeyAfter, "an intact retry must not replace the backing file");
    }

    /**
     * A missing backing file must never turn into a loophole that lets a genuinely different
     * republish through as a silent "repair": the asset's recorded checksum still exists (it's
     * normal persisted metadata, not the {@code @JsonIgnore}d content field), so this conflicts the
     * same as it would if the file were still there.
     */
    @Test
    void republishingWithDifferentContentConflictsEvenWhenTheBackingFileIsMissing(@TempDir Path dir)
            throws IOException {
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

        byte[] different = "different content".getBytes(StandardCharsets.UTF_8);
        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "a.txt", sha256Hex(different), "true", different));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("a.txt", e.getExtendedData().get("resourceId"));
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
