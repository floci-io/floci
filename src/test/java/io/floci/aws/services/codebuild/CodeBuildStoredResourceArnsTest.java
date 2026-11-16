package io.floci.aws.services.codebuild;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.codebuild.model.Build;
import io.floci.aws.services.codebuild.model.Project;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What IAM asks CodeBuild of a build a request names: the project CodeBuild holds the build under. */
class CodeBuildStoredResourceArnsTest {

    private static final String ACCOUNT = "000000000000";
    private static final String PROJECT = "arn:aws:codebuild:us-east-1:000000000000:project/web";

    @Test
    void aBuildReachesTheProjectItIsHeldUnderAndAnIdOfNoBuildReachesNone() {
        CodeBuildService codeBuild = mock(CodeBuildService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        Build build = new Build();
        build.setId("web:1");
        build.setProjectName("web");
        Project project = new Project();
        project.setArn(PROJECT);
        when(codeBuild.batchGetBuilds("us-east-1", ACCOUNT, List.of("web:1"))).thenReturn(List.of(build));
        when(codeBuild.batchGetProjects("us-east-1", List.of("web"))).thenReturn(List.of(project));
        CodeBuildStoredResourceArns stored = new CodeBuildStoredResourceArns(codeBuild, regionResolver);

        assertEquals(List.of(PROJECT), stored.through("id", "web:1", "us-east-1"));
        assertEquals(List.of(PROJECT), stored.through("ids[]", "web:1", "us-east-1"));
        // An id of no build it holds, whatever project it seems to name, reaches none, nor does another member.
        assertEquals(List.of(), stored.through("id", "web:2", "us-east-1"));
        assertEquals(List.of(), stored.through("projectName", "web:1", "us-east-1"));
    }
}
