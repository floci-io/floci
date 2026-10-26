package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageVersionAssetResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PublishPackageVersionResult;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackageVersion;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.PackageOriginConfig;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                Digests.sha256Hex(content), "false", content);

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
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content);

        CodeArtifactService restarted = newService(dir);
        PackageVersionAssetResult result = restarted.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);

        assertEquals("hello world", new String(result.asset().getContent(), StandardCharsets.UTF_8));
        assertEquals(published.packageVersion().getRevision(), result.packageVersionRevision());
    }

    /**
     * The fix for a real finding (hectorvent, PR #5484): {@code containerBackedAssetBytes} used
     * to be wired through {@code StorageFactory} like every other durable store here, but the
     * Maven/npm/pypi content it counts lives only in the sidecar containers themselves, which do
     * not survive a Floci restart either (no volume attached, removed on shutdown; see this
     * service's own Known limitations doc). A persisted count surviving a restart the content
     * itself does not would leave a stale total with nothing to ever clean it up.
     */
    @Test
    void containerBackedAssetBytesDoesNotSurviveRestart(@TempDir Path dir) {
        CodeArtifactService first = newService(dir);
        first.createDomain(REGION, "dom", null, Map.of());
        first.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        long fence = first.currentContainerBackedPackageFence(REGION, "dom", null, "repo", "maven", "com.example",
                "my-pkg");
        first.recordContainerBackedAssetBytes(REGION, "dom", null, "repo", "maven", "com.example", "my-pkg", 4096L,
                fence);
        assertEquals(4096L, first.describeDomain(REGION, "dom", null).assetSizeBytes());

        CodeArtifactService restarted = newService(dir);

        assertEquals(0L, restarted.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * {@code packageOriginConfigs} is wired through {@code StorageFactory} the same way
     * {@code domains}/{@code repositories}/{@code packageVersions} are, but that similarity had
     * never actually been exercised: this confirms a stored origin configuration, including one
     * set on a package with no versions at all, actually round-trips through disk rather than only
     * ever having been read back from the same in-memory instance that wrote it.
     */
    @Test
    void packageOriginConfigurationSurvivesRestart(@TempDir Path dir) {
        CodeArtifactService first = newService(dir);
        first.createDomain(REGION, "dom", null, Map.of());
        first.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        first.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "never-published", "BLOCK",
                "ALLOW");

        CodeArtifactService restarted = newService(dir);
        CodeArtifactService.PackageDescription described = restarted.describePackage(REGION, "dom", null, "repo",
                "generic", "ns", "never-published");
        assertEquals("BLOCK", described.publishRestriction());
        assertEquals("ALLOW", described.upstreamRestriction());
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
                Digests.sha256Hex(content), "true", content);

        CodeArtifactService restarted = newService(dir);
        PublishPackageVersionResult result = restarted.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "true", content);

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
                Digests.sha256Hex(content), "true", content);

        CodeArtifactService restarted = newService(dir);
        byte[] different = "different bytes".getBytes(StandardCharsets.UTF_8);
        AwsException e = assertThrows(AwsException.class, () -> restarted.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(different), "true", different));
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
                Digests.sha256Hex(content), "true", content);

        Path assetRoot = dir.resolve("codeartifact-assets");
        try (Stream<Path> paths = Files.walk(assetRoot)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                Files.delete(p);
            }
        }

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "true", content);

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
                Digests.sha256Hex(content), "false", content);

        Path assetRoot = dir.resolve("codeartifact-assets");
        try (Stream<Path> paths = Files.walk(assetRoot)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                Files.delete(p);
            }
        }

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content);

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
                Digests.sha256Hex(content), "true", content);

        Path assetRoot = dir.resolve("codeartifact-assets");
        try (Stream<Path> paths = Files.walk(assetRoot)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                Files.write(p, "corrupted on disk".getBytes(StandardCharsets.UTF_8));
            }
        }

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "true", content);

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
                Digests.sha256Hex(content), "true", content);

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
                            "a.txt", Digests.sha256Hex(content), "true", content));

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
                Digests.sha256Hex(content), "true", content);
        Path assetFile;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            assetFile = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Object fileKeyBefore = Files.readAttributes(assetFile, BasicFileAttributes.class).fileKey();
        assertNotNull(fileKeyBefore, "test filesystem must support file keys for this identity check to be meaningful");

        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "true", content);

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
                Digests.sha256Hex(content), "false", content);
        Path assetFile;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            assetFile = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Object fileKeyBefore = Files.readAttributes(assetFile, BasicFileAttributes.class).fileKey();
        assertNotNull(fileKeyBefore, "test filesystem must support file keys for this identity check to be meaningful");

        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

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
                Digests.sha256Hex(content), "true", content);

        Path assetRoot = dir.resolve("codeartifact-assets");
        try (Stream<Path> paths = Files.walk(assetRoot)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                Files.delete(p);
            }
        }

        byte[] different = "different content".getBytes(StandardCharsets.UTF_8);
        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(different), "true", different));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("a.txt", e.getExtendedData().get("resourceId"));
    }

    /**
     * {@code deletePackage} removes a version's metadata record before it attempts to clean up
     * that version's asset bytes, specifically so a cleanup failure on one asset never leaves the
     * record in place pointing at bytes that may now be missing. A {@link DirectoryNotEmptyException}
     * forced on one asset's path stands in for the failure here, since it is deterministic and
     * platform-independent (unlike a permission trick, which a root-run CI container would ignore).
     * The other asset in the same version must still get cleaned up, and the failure must be logged
     * rather than silently swallowed, the same way {@code republishingWithMatchingContentRepairsAnUnreadableBackingFileAndLogsAWarning}
     * above asserts on the real log record rather than trusting that the behavior exists.
     */
    @Test
    void deletingAnAssetsBytesFailingStillRemovesTheVersionRecordAndCleansUpTheOtherAsset(@TempDir Path dir)
            throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] contentA = "content of a".getBytes(StandardCharsets.UTF_8);
        byte[] contentB = "content of b".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(contentA), "true", contentA);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "b.txt",
                Digests.sha256Hex(contentB), "true", contentB);

        Path assetFileA;
        Path assetFileB;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            assetFileA = files.stream().filter(p -> matchesContent(p, contentA)).findFirst().orElseThrow();
            assetFileB = files.stream().filter(p -> matchesContent(p, contentB)).findFirst().orElseThrow();
        }
        Files.delete(assetFileB);
        Files.createDirectory(assetFileB);
        Files.createFile(assetFileB.resolve("not-empty"));

        List<LogRecord> records = LogCapture.capture(CodeArtifactService.class, () ->
                service.deletePackage(REGION, "dom", null, "repo", "generic", "ns", "my-pkg"));

        assertTrue(records.stream().anyMatch(r -> r.getMessage() != null
                        && r.getMessage().contains("Could not delete CodeArtifact asset file")
                        && r.getThrown() instanceof UncheckedIOException
                        && r.getThrown().getCause() instanceof DirectoryNotEmptyException),
                "expected a warning logging the failed asset cleanup, got: " + records);
        assertFalse(Files.exists(assetFileA), "the other asset's bytes must still be cleaned up");

        AwsException gone = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt", null));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    /**
     * Qodo review finding on the PR that added {@code DisposePackageVersions}: unlike {@code
     * deletePackage} (whose version record is already gone by the time its own best-effort asset
     * cleanup runs, so a failure there can never be reached again), a disposed version's record
     * is kept around: {@code DescribePackageVersion} must keep answering for it. The dispose path
     * used to catch and log a failed delete the same way {@code deletePackage} does, then still
     * mark the version Disposed and clear its asset map regardless, permanently orphaning bytes
     * that record could no longer point back to. The same {@link DirectoryNotEmptyException} trick
     * as {@link #deletingAnAssetsBytesFailingStillRemovesTheVersionRecordAndCleansUpTheOtherAsset}
     * forces a real delete failure.
     *
     * <p>Raised again by Greptile's review: this now also proves the status write itself runs
     * <em>before</em> the delete, not after (see {@code applyGenericStatusChange}'s own javadoc).
     * The version reads {@code Disposed} (blocking downloads immediately) rather than staying
     * {@code Published} with a broken asset still reachable, even though this one real asset's
     * own delete never actually succeeded.
     */
    @Test
    void disposePackageVersionsPropagatesAFailedAssetDeletionInsteadOfReportingSuccess(@TempDir Path dir)
            throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "v1".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        Path assetFile;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            assetFile = paths.filter(Files::isRegularFile).filter(p -> matchesContent(p, content))
                    .findFirst().orElseThrow();
        }
        Files.delete(assetFile);
        Files.createDirectory(assetFile);
        Files.createFile(assetFile.resolve("not-empty"));

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> service.disposePackageVersions(
                REGION, "dom", null, "repo", "generic", "ns", "pkg", List.of("1.0.0"), null, null));
        assertTrue(e.getCause() instanceof DirectoryNotEmptyException, "expected the real delete failure to "
                + "propagate rather than being swallowed, got cause: " + e.getCause());

        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "pkg", "1.0.0");
        assertEquals("Disposed", pv.getStatus(), "the status write runs before the delete, so a failed delete "
                + "must still leave the version blocked rather than reading as the untouched Published it no "
                + "longer really is");
        assertEquals(1, pv.getAssets().size(), "the one asset whose own delete failed is never removed from the "
                + "record (only a successfully deleted asset is), but is still unreachable through downloads "
                + "now that the version itself reads Disposed");
        AwsException blocked = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom",
                null, "repo", "generic", "ns", "pkg", "1.0.0", "a.txt", null));
        assertEquals("ResourceNotFoundException", blocked.getErrorCode());
    }

    /**
     * Raised by Greptile's review: proves the per-asset bookkeeping, not just the upfront status
     * write above. With two assets, one whose delete succeeds and one whose delete then fails,
     * the record must name only the one that genuinely never got deleted, not the one already
     * gone, so a retry (now let through for an already-{@code Disposed} version, see {@code
     * checkStatusTransitionAllowed}'s own javadoc) picks up exactly where this attempt stopped.
     */
    @Test
    void disposePackageVersionsRemovesEachAssetFromTheRecordRightAfterItsOwnSuccessfulDelete(@TempDir Path dir)
            throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] contentA = "content of a".getBytes(StandardCharsets.UTF_8);
        byte[] contentB = "content of b".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(contentA), "true", contentA);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "b.txt",
                Digests.sha256Hex(contentB), "true", contentB);

        Path assetFileA;
        Path assetFileB;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            assetFileA = files.stream().filter(p -> matchesContent(p, contentA)).findFirst().orElseThrow();
            assetFileB = files.stream().filter(p -> matchesContent(p, contentB)).findFirst().orElseThrow();
        }
        Files.delete(assetFileB);
        Files.createDirectory(assetFileB);
        Files.createFile(assetFileB.resolve("not-empty"));

        assertThrows(UncheckedIOException.class, () -> service.disposePackageVersions(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg", List.of("1.0.0"), null, null));

        assertFalse(Files.exists(assetFileA), "the asset whose own delete succeeded must still be gone");
        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "my-pkg", "1.0.0");
        assertEquals("Disposed", pv.getStatus());
        assertEquals(Set.of("b.txt"), pv.getAssets().keySet(), "only the asset whose delete never actually "
                + "succeeded should still be named in the record");
    }

    private static boolean matchesContent(Path file, byte[] expected) {
        try {
            return Arrays.equals(Files.readAllBytes(file), expected);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Found by Qodo review: {@code copyPackageVersions} used to read and write each version's
     * assets inline inside its own commit loop, so a later version's corrupted source bytes
     * would be discovered only after an earlier, otherwise-valid version in the same batch was
     * already committed to the destination, the per-call all-or-nothing contract AWS documents
     * for the caller-visible failure codes, silently broken for this emulator-internal one.
     * Every pending version's source assets are now verified against their persisted hashes
     * before any version in the batch has anything written to the destination at all.
     */
    @Test
    void copyPackageVersionsVerifiesEveryPendingVersionBeforeCommittingAnyOfThem(@TempDir Path dir)
            throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        byte[] contentGood = "version one, intact".getBytes(StandardCharsets.UTF_8);
        byte[] contentBad = "version two, about to be corrupted".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "src", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(contentGood), "false", contentGood);
        service.publishPackageVersion(REGION, "dom", null, "src", "generic", "ns", "my-pkg", "2.0.0", "a.txt",
                Digests.sha256Hex(contentBad), "false", contentBad);

        Path corruptedFile;
        try (Stream<Path> paths = Files.walk(dir.resolve("codeartifact-assets"))) {
            corruptedFile = paths.filter(Files::isRegularFile)
                    .filter(p -> matchesContent(p, contentBad))
                    .findFirst().orElseThrow();
        }
        Files.write(corruptedFile, "corrupted on disk".getBytes(StandardCharsets.UTF_8));

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0", "2.0.0"), null, false, false));
        assertEquals("InternalServerException", e.getErrorCode());

        AwsException notCopied = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION,
                "dom", null, "dst", "generic", "ns", "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", notCopied.getErrorCode(),
                "version 1.0.0 was valid on its own, but must not have been committed ahead of the batch "
                        + "discovering version 2.0.0's corrupted bytes");
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
                    name, Digests.sha256Hex(content), "true", content);
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
        AccountAwareStorageBackend<PackageOriginConfig> packageOriginConfigStore = accountAware(dir,
                "codeartifact-package-origin-configs.json", new TypeReference<Map<String, PackageOriginConfig>>() {});

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0, String.class) + ":" + invocation.getArgument(1, String.class)
                        + ":" + ACCOUNT_ID + ":" + invocation.getArgument(2, String.class));

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        return new CodeArtifactService(domainStore, repoStore, packageVersionStore, packageOriginConfigStore,
                regionResolver, config, false, dir.resolve("codeartifact-assets"),
                new CodeArtifactSidecarRegistry(List.of()));
    }

    private <V> AccountAwareStorageBackend<V> accountAware(Path dir, String fileName, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(fileName), type);
        backend.load();
        return new AccountAwareStorageBackend<>(backend, null, ACCOUNT_ID);
    }
}
