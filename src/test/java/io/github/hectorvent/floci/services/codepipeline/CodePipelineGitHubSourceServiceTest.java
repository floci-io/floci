package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ThirdParty GitHub (version 1) source actions: the branch archive is fetched, repackaged with
 * the repo contents at the artifact root, and handed to the next stage with unix modes intact.
 * The archive fetch is the only seam replaced; the artifact is observed through an S3 deploy
 * action that writes the input artifact to the mocked S3 service.
 */
class CodePipelineGitHubSourceServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private final S3Service s3Service = mock(S3Service.class);
    private final List<String> fetched = new ArrayList<>();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private static final long DEFAULT_CAP = 128L << 20;
    private static final int DEFAULT_ENTRIES = 100_000;

    private CodePipelineService service;
    private HttpServer server;
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @AfterEach
    void shutDown() {
        if (server != null) {
            server.stop(0);
        }
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void downloadsBranchArchiveAndPublishesRepoContentsAtTheArtifactRoot() throws Exception {
        // Catches: the codeload wrapper directory left in the artifact, or the wrong URL/revision recorded
        byte[] archive = zip(zos -> {
            dir(zos, "repo-main/");
            file(zos, "repo-main/README.md", "hello", 0, ZipEntry.DEFLATED);
            file(zos, "repo-main/src/app.ts", "code", 0, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive);
        createPipeline("github-sourced", "awslabs", "landing-zone-accelerator-on-aws", "release/v1.16.0");

        String executionId = start("github-sourced");
        JsonNode execution = awaitStatus("github-sourced", executionId, "Succeeded");

        assertEquals(List.of("https://codeload.github.com/awslabs/landing-zone-accelerator-on-aws"
                + "/zip/refs/heads/release/v1.16.0"), fetched);
        assertEquals("github.com/awslabs/landing-zone-accelerator-on-aws@release/v1.16.0",
                execution.path("artifactRevisions").get(0).path("revisionSummary").asText());
        try (ZipFile artifact = deployedArtifact("github-sourced-out.zip")) {
            assertEquals("hello", read(artifact, "README.md"));
            assertEquals("code", read(artifact, "src/app.ts"));
            assertNull(artifact.getEntry("repo-main/README.md"));
        }
    }

    @Test
    void rollbackFetchesTheCommitTheTargetExecutionResolved() throws Exception {
        // Catches: a rollback re-fetching the branch head instead of the commit the target resolved
        byte[] archive = zip(zos -> {
            dir(zos, "repo-main/");
            file(zos, "repo-main/README.md", "hello", 0, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive);
        createPipeline("github-rollback", "awslabs", "lza", "main");
        String targetId = start("github-rollback");
        awaitStatus("github-rollback", targetId, "Succeeded");

        String rollbackId = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "github-rollback")
                        .put("stageName", "Publish")
                        .put("targetPipelineExecutionId", targetId),
                REGION, ACCOUNT).path("pipelineExecutionId").asText();
        awaitStatus("github-rollback", rollbackId, "Succeeded");

        assertEquals(List.of("https://codeload.github.com/awslabs/lza/zip/refs/heads/main",
                "https://codeload.github.com/awslabs/lza/zip/" + SHA), fetched);
    }

    @Test
    void rejectsPathTraversalInOwnerRepoOrBranch() throws Exception {
        // Catches: a pipeline-declared Repo reshaping the codeload request path
        service = serviceServing(new byte[0]);
        createPipeline("traversal-repo", "awslabs", "../../etc", "main");

        String executionId = start("traversal-repo");
        awaitStatus("traversal-repo", executionId, "Failed");

        assertEquals(List.of(), fetched);
        JsonNode actions = service.handle("ListActionExecutions",
                mapper.createObjectNode().put("pipelineName", "traversal-repo"), REGION, ACCOUNT)
                .path("actionExecutionDetails");
        assertTrue(actions.toString().contains("must be non-empty and contain no"), actions.toString());
    }

    @Test
    void repackagingPreservesUnixFileModes() throws Exception {
        // Catches: unix mode bits dropped from the repackaged artifact entries
        byte[] archive = zip(zos -> {
            dir(zos, "repo-main/");
            file(zos, "repo-main/lib/bash/bootstrap.sh", "#!/bin/bash\n", 0755, ZipEntry.DEFLATED);
            file(zos, "repo-main/README.md", "hello", 0, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive);
        createPipeline("github-modes", "awslabs", "lza", "main");

        awaitStatus("github-modes", start("github-modes"), "Succeeded");

        try (ZipFile artifact = deployedArtifact("github-modes-out.zip")) {
            assertEquals(0755, artifact.getEntry("lib/bash/bootstrap.sh").getUnixMode());
            assertEquals(0, artifact.getEntry("README.md").getUnixMode());
        }
    }

    @Test
    void repackagingPreservesSymlinks() throws Exception {
        // Catches: symlink entries flattened in the artifact (mode and link target lost)
        byte[] archive = zip(zos -> {
            dir(zos, "repo-main/");
            file(zos, "repo-main/node_modules/ts-node/dist/bin.js", "#!/usr/bin/env node\n", 0755, ZipEntry.DEFLATED);
            file(zos, "repo-main/node_modules/.bin/ts-node", "../ts-node/dist/bin.js", 0120777, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive);
        createPipeline("github-symlinks", "awslabs", "lza", "main");

        awaitStatus("github-symlinks", start("github-symlinks"), "Succeeded");

        try (ZipFile artifact = deployedArtifact("github-symlinks-out.zip")) {
            ZipArchiveEntry link = artifact.getEntry("node_modules/.bin/ts-node");
            assertEquals(0xA000, link.getUnixMode() & 0xF000);
            assertEquals("../ts-node/dist/bin.js", read(artifact, "node_modules/.bin/ts-node"));
            assertEquals(0755, artifact.getEntry("node_modules/ts-node/dist/bin.js").getUnixMode());
        }
    }

    @Test
    void repackagesStoredAndDeflatedEntriesWithoutTheCodecDispatchPath() throws Exception {
        // Catches: entry decoding that only handles one method, or that routes through
        // ZipFile.getInputStream (ZSTD/XZ dispatch, which breaks the native image build)
        byte[] archive = zip(zos -> {
            dir(zos, "repo-main/");
            file(zos, "repo-main/stored.txt", "stored payload", 0644, ZipEntry.STORED);
            file(zos, "repo-main/deflated.txt", "deflated payload ".repeat(50), 0755, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive);
        createPipeline("github-methods", "awslabs", "lza", "main");

        awaitStatus("github-methods", start("github-methods"), "Succeeded");

        try (ZipFile artifact = deployedArtifact("github-methods-out.zip")) {
            assertEquals("stored payload", read(artifact, "stored.txt"));
            assertEquals(0644, artifact.getEntry("stored.txt").getUnixMode());
            assertEquals("deflated payload ".repeat(50), read(artifact, "deflated.txt"));
            assertEquals(0755, artifact.getEntry("deflated.txt").getUnixMode());
        }
    }

    @Test
    void failsWhenTheDownloadedArchiveExceedsTheSizeCap() throws Exception {
        // Catches: an oversized archive accepted and repackaged in memory instead of failing the action
        byte[] archive = zip(zos -> file(zos, "repo-main/big.bin", "x".repeat(4096), 0, ZipEntry.STORED));
        service = serviceServing(archive, 100, DEFAULT_CAP, DEFAULT_ENTRIES);
        createPipeline("github-big-download", "awslabs", "lza", "main");

        awaitStatus("github-big-download", start("github-big-download"), "Failed");

        assertFailureMessage("github-big-download", "maximum archive download size of 100 bytes");
        verifyNoInteractions(s3Service);
    }

    @Test
    void failsWhenUncompressedContentExceedsTheSizeCap() throws Exception {
        // Catches: a zip bomb (small archive, huge inflated content) repackaged without a total-bytes bound
        byte[] archive = zip(zos -> {
            file(zos, "repo-main/a.txt", "a".repeat(600), 0, ZipEntry.DEFLATED);
            file(zos, "repo-main/b.txt", "b".repeat(600), 0, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive, DEFAULT_CAP, 1000, DEFAULT_ENTRIES);
        createPipeline("github-bomb", "awslabs", "lza", "main");

        awaitStatus("github-bomb", start("github-bomb"), "Failed");

        assertFailureMessage("github-bomb", "maximum uncompressed size of 1000 bytes");
        verifyNoInteractions(s3Service);
    }

    @Test
    void failsWhenTheArchiveHasTooManyEntries() throws Exception {
        // Catches: an archive with an unbounded entry count repackaged instead of failing the action
        byte[] archive = zip(zos -> {
            file(zos, "repo-main/1.txt", "1", 0, ZipEntry.DEFLATED);
            file(zos, "repo-main/2.txt", "2", 0, ZipEntry.DEFLATED);
            file(zos, "repo-main/3.txt", "3", 0, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive, DEFAULT_CAP, DEFAULT_CAP, 2);
        createPipeline("github-many", "awslabs", "lza", "main");

        awaitStatus("github-many", start("github-many"), "Failed");

        assertFailureMessage("github-many", "maximum of 2 entries");
        verifyNoInteractions(s3Service);
    }

    @Test
    void rejectsAbsoluteEntryNames() throws Exception {
        // Catches: a leading-slash entry whose slash is eaten as the top-level separator and so slips past validation
        byte[] archive = zip(zos -> {
            file(zos, "repo-main/ok.txt", "ok", 0, ZipEntry.DEFLATED);
            file(zos, "/repo-main/evil.txt", "evil", 0, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive);
        createPipeline("github-absolute", "awslabs", "lza", "main");

        awaitStatus("github-absolute", start("github-absolute"), "Failed");

        assertFailureMessage("github-absolute", "escaping entry");
        verifyNoInteractions(s3Service);
    }

    @Test
    void rejectsEntriesWithParentDirectorySegments() throws Exception {
        // Catches: a ../ entry repackaged into the artifact so extraction writes outside the workspace
        byte[] archive = zip(zos -> {
            file(zos, "repo-main/ok.txt", "ok", 0, ZipEntry.DEFLATED);
            file(zos, "repo-main/../../evil.txt", "evil", 0, ZipEntry.DEFLATED);
        });
        service = serviceServing(archive);
        createPipeline("github-dotdot", "awslabs", "lza", "main");

        awaitStatus("github-dotdot", start("github-dotdot"), "Failed");

        assertFailureMessage("github-dotdot", "escaping entry");
        verifyNoInteractions(s3Service);
    }

    @Test
    void rejectsSymlinksResolvingOutsideTheRoot() throws Exception {
        // Catches: a relative symlink climbing out of the root repackaged as-is
        byte[] archive = zip(zos -> file(zos, "repo-main/a/link", "../../outside", 0120777, ZipEntry.DEFLATED));
        service = serviceServing(archive);
        createPipeline("github-link-out", "awslabs", "lza", "main");

        awaitStatus("github-link-out", start("github-link-out"), "Failed");

        assertFailureMessage("github-link-out", "escaping symlink");
        verifyNoInteractions(s3Service);
    }

    @Test
    void rejectsSymlinksWithAbsoluteTargets() throws Exception {
        // Catches: an absolute symlink target (e.g. /etc/passwd) repackaged into the artifact
        byte[] archive = zip(zos -> file(zos, "repo-main/link", "/etc/passwd", 0120777, ZipEntry.DEFLATED));
        service = serviceServing(archive);
        createPipeline("github-link-abs", "awslabs", "lza", "main");

        awaitStatus("github-link-abs", start("github-link-abs"), "Failed");

        assertFailureMessage("github-link-abs", "escaping symlink");
        verifyNoInteractions(s3Service);
    }

    @Test
    void doesNotInterceptCustomActionsNamedGitHub() throws Exception {
        // Catches: a Custom-owner Source action with provider GitHub downloaded from codeload instead of reaching its worker
        service = serviceServing(zip(zos -> file(zos, "repo-main/a.txt", "a", 0, ZipEntry.DEFLATED)));
        createPipeline("github-custom", "Custom", "1", "awslabs", "lza", "main");

        String executionId = start("github-custom");

        // The action must stay with its worker for a sustained window: no codeload fetch, execution still InProgress.
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(List.of(), fetched);
            assertEquals("InProgress", service.handle("GetPipelineExecution", mapper.createObjectNode()
                            .put("pipelineName", "github-custom").put("pipelineExecutionId", executionId),
                    REGION, ACCOUNT).path("pipelineExecution").path("status").asText());
        });
    }

    @Test
    void recordsTheCommitFromTheArchiveCommentAsTheSourceRevision() throws Exception {
        // Catches: the branch name reported as the revision, or no sourceRevisions entry for ListPipelineExecutions
        service = serviceServing(zip(zos -> file(zos, "repo-main/a.txt", "a", 0, ZipEntry.DEFLATED)));
        createPipeline("github-commit", "awslabs", "lza", "main");

        JsonNode execution = awaitStatus("github-commit", start("github-commit"), "Succeeded");

        JsonNode artifactRevision = execution.path("artifactRevisions").get(0);
        assertEquals(SHA, artifactRevision.path("revisionId").asText());
        assertEquals(SHA, artifactRevision.path("revisionChangeIdentifier").asText());
        assertEquals("https://github.com/awslabs/lza/commit/" + SHA, artifactRevision.path("revisionUrl").asText());
        JsonNode sourceRevision = service.handle("ListPipelineExecutions",
                mapper.createObjectNode().put("pipelineName", "github-commit"), REGION, ACCOUNT)
                .path("pipelineExecutionSummaries").get(0).path("sourceRevisions").get(0);
        assertEquals("GitHubSource", sourceRevision.path("actionName").asText());
        assertEquals(SHA, sourceRevision.path("revisionId").asText());
        assertEquals("https://github.com/awslabs/lza/commit/" + SHA, sourceRevision.path("revisionUrl").asText());
    }

    @Test
    void failsWhenTheArchiveCarriesNoCommitSha() throws Exception {
        // Catches: a silent fallback to the branch name when the archive comment is not a commit SHA
        service = serviceServing(zip(null, zos -> file(zos, "repo-main/a.txt", "a", 0, ZipEntry.DEFLATED)));
        createPipeline("github-nosha", "awslabs", "lza", "main");

        awaitStatus("github-nosha", start("github-nosha"), "Failed");

        assertFailureMessage("github-nosha", "commit SHA");
        verifyNoInteractions(s3Service);
    }

    @Test
    void encodesBranchCharactersThatAreSpecialInAUri() throws Exception {
        // Catches: a '#' branch turned into a URI fragment (downloading the wrong ref) or '%' breaking the URI
        byte[] archive = zip(zos -> file(zos, "repo-main/a.txt", "a", 0, ZipEntry.DEFLATED));
        startArchiveServer(archive, false, DEFAULT_CAP);
        createPipeline("github-hash", "awslabs", "lza", "feat#1/x%y");

        awaitStatus("github-hash", start("github-hash"), "Succeeded");

        assertEquals(List.of("/awslabs/lza/zip/refs/heads/feat%231/x%25y"), requestedPaths);
    }

    @Test
    void abortsAChunkedDownloadThatExceedsTheCapWhileItIsRead() throws Exception {
        // Catches: a response without Content-Length buffered in full before the size cap is applied
        byte[] archive = zip(zos -> file(zos, "repo-main/big.bin", "x".repeat(4096), 0, ZipEntry.STORED));
        startArchiveServer(archive, true, 100);
        createPipeline("github-chunked", "awslabs", "lza", "main");

        awaitStatus("github-chunked", start("github-chunked"), "Failed");

        assertFailureMessage("github-chunked", "maximum archive download size of 100 bytes");
        verifyNoInteractions(s3Service);
    }

    @Test
    void rejectsAnEntryWhoseContentDoesNotMatchItsRecordedChecksum() throws Exception {
        // Catches: a corrupted entry re-checksummed by the repackaging and reported as a good source
        byte[] archive = zip(zos -> file(zos, "repo-main/a.txt", "hello world", 0, ZipEntry.STORED));
        String text = new String(archive, StandardCharsets.ISO_8859_1);
        byte[] corrupt = text.replaceFirst("hello world", "jello world").getBytes(StandardCharsets.ISO_8859_1);
        service = serviceServing(corrupt);
        createPipeline("github-crc", "awslabs", "lza", "main");

        awaitStatus("github-crc", start("github-crc"), "Failed");

        assertFailureMessage("github-crc", "checksum");
        verifyNoInteractions(s3Service);
    }

    private void startArchiveServer(byte[] archive, boolean chunked, long archiveCap) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(200, chunked ? 0 : archive.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(archive);
            } catch (IOException ignored) {
                // the client aborts an oversized chunked download mid-stream
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new CodePipelineService(new InMemoryStorageFactory(), mapper, mock(CodeBuildService.class),
                mock(CodeDeployService.class), mock(LambdaService.class), s3Service) {
            @Override
            protected String githubArchiveBaseUrl() {
                return baseUrl;
            }

            @Override
            protected long maxArchiveBytes() {
                return archiveCap;
            }
        };
    }

    private void assertFailureMessage(String pipelineName, String expected) {
        JsonNode actions = service.handle("ListActionExecutions",
                mapper.createObjectNode().put("pipelineName", pipelineName), REGION, ACCOUNT)
                .path("actionExecutionDetails");
        assertTrue(actions.toString().contains(expected), actions.toString());
    }

    private CodePipelineService serviceServing(byte[] archive) {
        return serviceServing(archive, DEFAULT_CAP, DEFAULT_CAP, DEFAULT_ENTRIES);
    }

    private CodePipelineService serviceServing(byte[] archive, long archiveCap, long uncompressedCap,
                                               int entryCap) {
        return new CodePipelineService(new InMemoryStorageFactory(), mapper, mock(CodeBuildService.class),
                mock(CodeDeployService.class), mock(LambdaService.class), s3Service) {
            @Override
            byte[] fetchGitHubArchive(URI uri) {
                fetched.add(uri.toString());
                return archive;
            }

            @Override
            protected long maxArchiveBytes() {
                return archiveCap;
            }

            @Override
            protected long maxUncompressedBytes() {
                return uncompressedCap;
            }

            @Override
            protected int maxEntries() {
                return entryCap;
            }
        };
    }

    private void createPipeline(String name, String owner, String repo, String branch) throws Exception {
        createPipeline(name, "ThirdParty", "1", owner, repo, branch);
    }

    private void createPipeline(String name, String actionOwner, String actionVersion, String owner,
                                String repo, String branch) throws Exception {
        service.handle("CreatePipeline", mapper.readTree("""
                {"pipeline": {
                    "name": "%s",
                    "roleArn": "arn:aws:iam::000000000000:role/cp",
                    "artifactStore": {"type": "S3", "location": "bucket"},
                    "stages": [{
                        "name": "Fetch",
                        "actions": [{
                            "name": "GitHubSource",
                            "actionTypeId": {"category": "Source", "owner": "%s", "provider": "GitHub", "version": "%s"},
                            "configuration": {"Owner": "%s", "Repo": "%s", "Branch": "%s"},
                            "outputArtifacts": [{"name": "Source"}],
                            "runOrder": 1
                        }]
                    }, {
                        "name": "Publish",
                        "actions": [{
                            "name": "WriteOut",
                            "actionTypeId": {"category": "Deploy", "owner": "AWS", "provider": "S3", "version": "1"},
                            "configuration": {"BucketName": "out", "ObjectKey": "%s-out.zip"},
                            "inputArtifacts": [{"name": "Source"}],
                            "runOrder": 1
                        }]
                    }]
                }}
                """.formatted(name, actionOwner, actionVersion, owner, repo, branch, name)), REGION, ACCOUNT);
    }

    private String start(String pipelineName) {
        return service.handle("StartPipelineExecution",
                mapper.createObjectNode().put("name", pipelineName), REGION, ACCOUNT)
                .path("pipelineExecutionId").asText();
    }

    private JsonNode awaitStatus(String pipelineName, String executionId, String expected) {
        return await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25))
                .until(() -> service.handle("GetPipelineExecution", mapper.createObjectNode()
                                .put("pipelineName", pipelineName).put("pipelineExecutionId", executionId),
                        REGION, ACCOUNT).path("pipelineExecution"),
                        execution -> expected.equals(execution.path("status").asText()));
    }

    private ZipFile deployedArtifact(String objectKey) throws Exception {
        ArgumentCaptor<byte[]> data = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("out"), eq(objectKey), data.capture(),
                eq("application/zip"), eq(Map.of()));
        return ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(data.getValue())).get();
    }

    private static String read(ZipFile zip, String name) throws Exception {
        // Raw bytes: the artifact is repackaged with DEFLATED or STORED entries only.
        byte[] raw = zip.getRawInputStream(zip.getEntry(name)).readAllBytes();
        if (zip.getEntry(name).getMethod() == ZipEntry.STORED) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try (InflaterInputStream in = new InflaterInputStream(
                new ByteArrayInputStream(raw), new Inflater(true))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private interface ZipWriter {
        void write(ZipArchiveOutputStream zos) throws Exception;
    }

    private static byte[] zip(ZipWriter writer) throws Exception {
        return zip(SHA, writer);
    }

    private static byte[] zip(String comment, ZipWriter writer) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            if (comment != null) {
                zos.setComment(comment);
            }
            writer.write(zos);
        }
        return out.toByteArray();
    }

    private static void dir(ZipArchiveOutputStream zos, String name) throws Exception {
        zos.putArchiveEntry(new ZipArchiveEntry(name));
        zos.closeArchiveEntry();
    }

    private static void file(ZipArchiveOutputStream zos, String name, String content, int unixMode, int method)
            throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        if (unixMode != 0) {
            entry.setUnixMode(unixMode);
        }
        zos.putArchiveEntry(entry);
        zos.write(bytes);
        zos.closeArchiveEntry();
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }
}
