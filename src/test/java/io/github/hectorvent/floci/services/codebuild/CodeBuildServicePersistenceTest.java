package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
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
        CodeBuildService service = new CodeBuildService(
                mock(CodeBuildRunner.class), mock(EmulatorConfig.class), storage);
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
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
