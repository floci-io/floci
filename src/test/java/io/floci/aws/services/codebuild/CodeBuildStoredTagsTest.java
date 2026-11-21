package io.floci.aws.services.codebuild;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.codebuild.model.Project;
import io.floci.aws.services.codebuild.model.ProjectArtifacts;
import io.floci.aws.services.codebuild.model.ProjectEnvironment;
import io.floci.aws.services.codebuild.model.ProjectSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeBuildStoredTagsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private CodeBuildService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        service = new CodeBuildService(mock(CodeBuildRunner.class), mock(EmulatorConfig.class), storageFactory);
        service.initializeStorage();
    }

    @Test
    void storedTagsReadTheProjectOrTheReportGroupTheArnNamesInItsAccountAndRegion() {
        String project = createProject("web", List.of(tag("team", "data"))).getArn();
        String reportGroup = service.createReportGroup(REGION, ACCOUNT, "tests", "TEST",
                Map.of("exportConfigType", "NO_EXPORT"), List.of(tag("team", "qa"))).getArn();

        assertEquals(Map.of("team", "data"), service.storedTags(project));
        assertEquals(Map.of("team", "qa"), service.storedTags(reportGroup));
        assertEquals(Map.of(), service.storedTags(project.replace("web", "api")));
        assertEquals(Map.of(), service.storedTags(project.replace(ACCOUNT, "111111111111")));
        assertEquals(Map.of(), service.storedTags(project.replace(REGION, "us-west-2")));
        assertEquals(Map.of(), service.storedTags(reportGroup.replace(ACCOUNT, "111111111111")));
        assertEquals(Map.of(), service.storedTags(createProject("untagged", null).getArn()));
    }

    private Project createProject(String name, List<Map<String, String>> tags) {
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        return service.createProject(REGION, ACCOUNT, name, null, source, null, null, artifacts, null,
                new ProjectEnvironment(), "arn:aws:iam::" + ACCOUNT + ":role/codebuild", null, null, null, tags,
                null, null, null);
    }

    private static Map<String, String> tag(String key, String value) {
        return Map.of("key", key, "value", value);
    }
}
