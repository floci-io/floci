package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CodeBuildService.startBuild} always resolves {@code build.getEnvironment()} to the
 * fully-merged environment before persisting the Build, so the runner must trust that value
 * alone rather than ORing it with the project's own privileged-mode flag — an OR would make an
 * explicit {@code privilegedModeOverride=false} unable to disable a privileged project.
 */
class CodeBuildRunnerPrivilegedModeTest {

    @Test
    void explicitFalseOverrideDisablesAPrivilegedProject() {
        Project project = new Project();
        ProjectEnvironment projectEnv = new ProjectEnvironment();
        projectEnv.setPrivilegedMode(true);
        project.setEnvironment(projectEnv);

        Build build = new Build();
        ProjectEnvironment resolvedBuildEnv = new ProjectEnvironment();
        resolvedBuildEnv.setPrivilegedMode(false);
        build.setEnvironment(resolvedBuildEnv);

        assertFalse(CodeBuildRunner.resolvePrivilegedMode(project, build));
    }

    @Test
    void unsetBuildEnvironmentFallsBackToTheProjectSetting() {
        Project project = new Project();
        ProjectEnvironment projectEnv = new ProjectEnvironment();
        projectEnv.setPrivilegedMode(true);
        project.setEnvironment(projectEnv);

        Build build = new Build();

        assertTrue(CodeBuildRunner.resolvePrivilegedMode(project, build));
    }

    @Test
    void resolvedBuildEnvironmentWithoutAnExplicitFlagIsNotPrivileged() {
        Project project = new Project();
        ProjectEnvironment projectEnv = new ProjectEnvironment();
        projectEnv.setPrivilegedMode(true);
        project.setEnvironment(projectEnv);

        Build build = new Build();
        build.setEnvironment(new ProjectEnvironment());

        assertFalse(CodeBuildRunner.resolvePrivilegedMode(project, build));
    }
}
