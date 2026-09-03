package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.codebuild.model.ReportGroup;
import io.github.hectorvent.floci.services.codebuild.model.SourceCredential;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Verifies CodeBuild durable resources survive a restart. Two service instances share the same
 * {@link StorageFactory} backends; the second simulates a process restart reloading from disk.
 */
class CodeBuildServicePersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Test
    void durableResourcesSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeBuildService first = serviceWithStorage(storage);
        first.createProject(REGION, ACCOUNT, "build-proj", "demo",
                source("GITHUB"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                30, null, null, List.of(Map.of("key", "team", "value", "platform")), null, null, null);
        first.createReportGroup(REGION, ACCOUNT, "rg-1", "TEST", null,
                List.of(Map.of("key", "env", "value", "test")));
        SourceCredential cred = first.importSourceCredentials(REGION, ACCOUNT,
                "tok-secret", "GITHUB", "PERSONAL_ACCESS_TOKEN", true);

        CodeBuildService reloaded = serviceWithStorage(storage);

        List<Project> projects = reloaded.batchGetProjects(REGION, List.of("build-proj"));
        assertEquals(1, projects.size());
        assertEquals("platform", projects.getFirst().getTags().getFirst().get("value"));
        assertEquals(List.of("rg-1"), reloaded.listReportGroups(REGION).stream()
                .map(arn -> reloaded.batchGetReportGroups(REGION, List.of(arn)).getFirst().getName()).toList());
        List<SourceCredential> creds = reloaded.listSourceCredentials(REGION);
        assertEquals(1, creds.size());
        assertEquals(cred.getArn(), creds.getFirst().getArn());
    }

    @Test
    void projectUpdateAndDeleteArePersistedAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeBuildService first = serviceWithStorage(storage);
        first.createProject(REGION, ACCOUNT, "p1", "original",
                source("GITHUB"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        // in-place mutation: update must be written back through StorageBackedMap
        first.updateProject(REGION, "p1", "updated", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        first.createProject(REGION, ACCOUNT, "p2", "to-delete",
                source("GITHUB"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        first.deleteProject(REGION, "p2");

        CodeBuildService reloaded = serviceWithStorage(storage);
        assertEquals("updated", reloaded.batchGetProjects(REGION, List.of("p1")).getFirst().getDescription());
        assertTrue(reloaded.batchGetProjects(REGION, List.of("p2")).isEmpty(),
                "deleted project must not reappear after restart");
        assertNull(reloaded.batchGetProjects(REGION, List.of("p2")).stream().findFirst().orElse(null));
    }

    @Test
    void startAndRetryBuildResponsesUseAcceptedBuildSnapshot() {
        CodeBuildRunner runner = mock(CodeBuildRunner.class);
        doAnswer(invocation -> {
            Build build = invocation.getArgument(1, Build.class);
            build.setBuildStatus("FAILED");
            build.setBuildComplete(true);
            build.setCurrentPhase("COMPLETED");
            return null;
        }).when(runner).startBuild(any(), any(), any(), any());

        CodeBuildService service = serviceWithStorage(new SharedStorageFactory(), runner);
        service.createProject(REGION, ACCOUNT, "p1", "demo",
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);

        Build startResponse = service.startBuild(REGION, ACCOUNT, "p1", null,
                null, null, null, null, null, null, null, null, null, null);
        assertEquals("IN_PROGRESS", startResponse.getBuildStatus());
        assertEquals(false, startResponse.getBuildComplete());
        assertEquals("SUBMITTED", startResponse.getCurrentPhase());
        assertEquals("FAILED", service.getBuild(REGION, startResponse.getId()).getBuildStatus());

        Build retryResponse = service.retryBuild(REGION, ACCOUNT, startResponse.getId());
        assertEquals("IN_PROGRESS", retryResponse.getBuildStatus());
        assertEquals(false, retryResponse.getBuildComplete());
        assertEquals("SUBMITTED", retryResponse.getCurrentPhase());
        assertTrue(retryResponse.getBuildNumber() > startResponse.getBuildNumber());
        assertEquals("FAILED", service.getBuild(REGION, retryResponse.getId()).getBuildStatus());
    }

    @Test
    void retryBuildReplaysTheOriginalBuildsEnvironmentNotTheCurrentProjects() {
        CodeBuildRunner runner = mock(CodeBuildRunner.class);
        CodeBuildService service = serviceWithStorage(new SharedStorageFactory(), runner);
        ProjectEnvironment original = new ProjectEnvironment();
        original.setImage("aws/codebuild/standard:6.0");
        original.setComputeType("BUILD_GENERAL1_SMALL");
        original.setEnvironmentVariables(List.of(Map.of("name", "STAGE", "value", "original-build")));
        service.createProject(REGION, ACCOUNT, "p1", "demo",
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                original, "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);
        Build started = service.startBuild(REGION, ACCOUNT, "p1", null,
                null, null, null, null, null, null, null, null, null, null);

        // The project changes after the build ran (a common reason to retry against an older
        // definition rather than whatever the project looks like now).
        ProjectEnvironment updated = new ProjectEnvironment();
        updated.setImage("aws/codebuild/standard:7.0");
        updated.setComputeType("BUILD_GENERAL1_LARGE");
        updated.setEnvironmentVariables(List.of(Map.of("name", "STAGE", "value", "current-project")));
        service.updateProject(REGION, "p1", null, null, null, null, null, null, updated,
                null, null, null, null, null, null, null, null);

        Build retried = service.retryBuild(REGION, ACCOUNT, started.getId());

        assertEquals("aws/codebuild/standard:6.0", retried.getEnvironment().getImage());
        assertEquals("BUILD_GENERAL1_SMALL", retried.getEnvironment().getComputeType());
        assertEquals(List.of(Map.of("name", "STAGE", "value", "original-build")),
                retried.getEnvironment().getEnvironmentVariables());
    }

    @Test
    void startBuildWithSparseEnvironmentTypeOverrideRetainsProjectImageAndComputeType() {
        CodeBuildService service = serviceWithStorage(new SharedStorageFactory());
        ProjectEnvironment environment = new ProjectEnvironment();
        environment.setType("LINUX_CONTAINER");
        environment.setImage("aws/codebuild/standard:7.0");
        environment.setComputeType("BUILD_GENERAL1_SMALL");
        environment.setPrivilegedMode(true);
        service.createProject(REGION, ACCOUNT, "p1", "demo",
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                environment, "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);

        // Only environmentTypeOverride is present on the request: CodeBuildJsonHandler.buildEnvOverride
        // builds a sparse ProjectEnvironment carrying just the type, leaving image/computeType/
        // privilegedMode null. The merge must still fall back to the project's own environment for
        // those, not to this sparse override object.
        ProjectEnvironment sparseOverride = new ProjectEnvironment();
        sparseOverride.setType("LINUX_CONTAINER");
        Build response = service.startBuild(REGION, ACCOUNT, "p1", null,
                sparseOverride, null, null, null, null, null, null, null, null, null);

        Build stored = service.getBuild(REGION, response.getId());
        assertEquals("aws/codebuild/standard:7.0", stored.getEnvironment().getImage());
        assertEquals("BUILD_GENERAL1_SMALL", stored.getEnvironment().getComputeType());
        assertEquals(Boolean.TRUE, stored.getEnvironment().getPrivilegedMode());
    }

    @Test
    void startBuildMergesEnvironmentVariableAndSecondarySourceOverrides() {
        CodeBuildService service = serviceWithStorage(new SharedStorageFactory());
        ProjectEnvironment environment = new ProjectEnvironment();
        environment.setEnvironmentVariables(List.of(Map.of("name", "STAGE", "value", "project")));
        service.createProject(REGION, ACCOUNT, "p1", "demo",
                source("NO_SOURCE"), null, null, artifacts("NO_ARTIFACTS"), null,
                environment, "arn:aws:iam::" + ACCOUNT + ":role/cb",
                null, null, null, null, null, null, null);

        ProjectSource secondary = source("S3");
        secondary.setLocation("bucket/codepipeline/exec-1/Config.zip");
        secondary.setSourceIdentifier("Config");
        Build response = service.startBuild(REGION, ACCOUNT, "p1", null,
                null, List.of(Map.of("name", "STAGE", "value", "override", "type", "PLAINTEXT")),
                null, null, null, null, List.of(secondary), null, null, null);

        Build stored = service.getBuild(REGION, response.getId());
        assertEquals(List.of(
                Map.of("name", "STAGE", "value", "project"),
                Map.of("name", "STAGE", "value", "override", "type", "PLAINTEXT")),
                stored.getEnvironment().getEnvironmentVariables());
        assertEquals(1, stored.getSecondarySources().size());
        assertEquals("S3", stored.getSecondarySources().getFirst().getType());
        assertEquals("bucket/codepipeline/exec-1/Config.zip",
                stored.getSecondarySources().getFirst().getLocation());
        assertEquals("Config", stored.getSecondarySources().getFirst().getSourceIdentifier());
        assertEquals("Config", response.getSecondarySources().getFirst().getSourceIdentifier());
    }

    private static ProjectSource source(String type) {
        ProjectSource s = new ProjectSource();
        s.setType(type);
        return s;
    }

    private static ProjectArtifacts artifacts(String type) {
        ProjectArtifacts a = new ProjectArtifacts();
        a.setType(type);
        return a;
    }

    private static CodeBuildService serviceWithStorage(StorageFactory storage) {
        return serviceWithStorage(storage, mock(CodeBuildRunner.class));
    }

    private static CodeBuildService serviceWithStorage(StorageFactory storage, CodeBuildRunner runner) {
        CodeBuildService service = new CodeBuildService(
                runner, mock(EmulatorConfig.class), storage);
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
