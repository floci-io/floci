package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CodeBuildServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Test
    void retryBuildRetainsOriginalBuildspecOverride() {
        CodeBuildRunner runner = mock(CodeBuildRunner.class);
        CodeBuildService service = new CodeBuildService(runner, mock(EmulatorConfig.class), null);
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");

        service.createProject(REGION, ACCOUNT, "retry-project", null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);

        String buildspec = "version: 0.2\nphases:\n  build:\n    commands:\n      - echo retry\n";
        Build original = service.startBuild(REGION, ACCOUNT, "retry-project", buildspec,
                null, null, null, null, null, null, null, null, null, null);
        Build retried = service.retryBuild(REGION, ACCOUNT, original.getId());

        assertNotEquals(original.getId(), retried.getId());
        ArgumentCaptor<String> buildspecOverrides = ArgumentCaptor.forClass(String.class);
        verify(runner, times(2)).startBuild(eq(REGION), any(Build.class), any(Project.class),
                buildspecOverrides.capture());
        assertEquals(List.of(buildspec, buildspec), buildspecOverrides.getAllValues());
    }

    /**
     * Greptile P1 on #2707: RetryBuild passes the original build's secondarySources straight
     * through as startBuild's override, and an empty list there is indistinguishable from "no
     * override" — so if the project gained secondary sources after the original build ran, the
     * retry silently picked up inputs the original build never used.
     */
    @Test
    void retryBuildDoesNotAdoptSecondarySourcesAddedToTheProjectAfterTheOriginalBuildRan() {
        CodeBuildRunner runner = mock(CodeBuildRunner.class);
        CodeBuildService service = new CodeBuildService(runner, mock(EmulatorConfig.class), null);
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");

        service.createProject(REGION, ACCOUNT, "retry-secondary-project", null, source, List.of(), null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);

        Build original = service.startBuild(REGION, ACCOUNT, "retry-secondary-project", null,
                null, null, null, null, null, null, null, null, null, null);
        assertEquals(List.of(), original.getSecondarySources(), "the original build ran with no secondary sources");

        ProjectSource added = new ProjectSource();
        added.setType("NO_SOURCE");
        added.setSourceIdentifier("added-after-the-fact");
        service.updateProject(REGION, "retry-secondary-project", null, null, List.of(added), null,
                null, null, null, null, null, null, null, null, null, null, null);

        Build retried = service.retryBuild(REGION, ACCOUNT, original.getId());

        assertEquals(List.of(), retried.getSecondarySources(),
                "a retry must reproduce the original build's inputs, not whatever the project has now");
    }
}
