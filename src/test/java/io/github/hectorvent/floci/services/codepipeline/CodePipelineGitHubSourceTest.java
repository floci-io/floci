package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ThirdParty GitHub (version 1) source actions: the branch archive is fetched, repackaged with
 * the repo contents at the artifact root, and handed to the next stage with unix modes intact.
 * The archive fetch is the only seam replaced; the artifact is observed through an S3 deploy
 * action that writes the input artifact to the mocked S3 service.
 */
class CodePipelineGitHubSourceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private final S3Service s3Service = mock(S3Service.class);
    private final List<String> fetched = new ArrayList<>();
    private CodePipelineService service;

    @AfterEach
    void shutDown() {
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
        assertEquals("release/v1.16.0",
                execution.path("artifactRevisions").get(0).path("revisionId").asText());
        assertEquals("github.com/awslabs/landing-zone-accelerator-on-aws@release/v1.16.0",
                execution.path("artifactRevisions").get(0).path("revisionSummary").asText());
        try (ZipFile artifact = deployedArtifact("github-sourced-out.zip")) {
            assertEquals("hello", read(artifact, "README.md"));
            assertEquals("code", read(artifact, "src/app.ts"));
            assertNull(artifact.getEntry("repo-main/README.md"));
        }
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
        assertTrue(actions.toString().contains("path separators or traversal"), actions.toString());
    }

    @Test
    void repackagingPreservesUnixFileModes() throws Exception {
        // Catches: executable bits dropped so bootstrap scripts fail to run in CodeBuild
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
        // Catches: symlink entries flattened, breaking node_modules/.bin executables in CodeBuild
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

    private CodePipelineService serviceServing(byte[] archive) {
        return new CodePipelineService(new InMemoryStorageFactory(), mapper, mock(CodeBuildService.class),
                mock(CodeDeployService.class), mock(LambdaService.class), s3Service) {
            @Override
            byte[] fetchGitHubArchive(URI uri) {
                fetched.add(uri.toString());
                return archive;
            }
        };
    }

    private void createPipeline(String name, String owner, String repo, String branch) throws Exception {
        service.handle("CreatePipeline", mapper.readTree("""
                {"pipeline": {
                    "name": "%s",
                    "roleArn": "arn:aws:iam::000000000000:role/cp",
                    "artifactStore": {"type": "S3", "location": "bucket"},
                    "stages": [{
                        "name": "Fetch",
                        "actions": [{
                            "name": "GitHubSource",
                            "actionTypeId": {"category": "Source", "owner": "ThirdParty", "provider": "GitHub", "version": "1"},
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
                """.formatted(name, owner, repo, branch, name)), REGION, ACCOUNT);
    }

    private String start(String pipelineName) {
        return service.handle("StartPipelineExecution",
                mapper.createObjectNode().put("name", pipelineName), REGION, ACCOUNT)
                .path("pipelineExecutionId").asText();
    }

    private JsonNode awaitStatus(String pipelineName, String executionId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonNode execution;
        do {
            execution = service.handle("GetPipelineExecution", mapper.createObjectNode()
                            .put("pipelineName", pipelineName).put("pipelineExecutionId", executionId),
                    REGION, ACCOUNT).path("pipelineExecution");
            if (expected.equals(execution.path("status").asText())) {
                return execution;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Pipeline did not reach " + expected
                + "; last status was " + execution.path("status").asText());
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
        try (var in = new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(raw), new java.util.zip.Inflater(true))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private interface ZipWriter {
        void write(ZipArchiveOutputStream zos) throws Exception;
    }

    private static byte[] zip(ZipWriter writer) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
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
