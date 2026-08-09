package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs real builds in Docker containers to verify that all buildspec phases share
 * one shell session — unexported variables and the working directory persist across
 * phases like on real CodeBuild, while shell options (set -e) set by one command
 * entry never leak into the next — and that per-phase status, duration and failure
 * contexts on the Build object keep working. The bash image exercises the primary
 * per-entry child-shell driver; busybox (no bash) exercises the sh fallback.
 */
@QuarkusTest
class CodeBuildRunnerDockerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String BASH_IMAGE = "public.ecr.aws/docker/library/bash:latest";
    private static final String SH_IMAGE = "public.ecr.aws/docker/library/busybox:latest";

    @Inject
    DockerClient dockerClient;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for CodeBuild runner tests");
    }

    @Test
    void shellStateAndWorkingDirectoryPersistAcrossPhases() {
        String project = "shell-state-" + Long.toString(System.nanoTime(), 36);
        createProject(project, BASH_IMAGE);
        String buildId = startBuild(project, """
                version: 0.2
                phases:
                  pre_build:
                    commands:
                      - set -e && ENABLE_EXTERNAL_PIPELINE_ACCOUNT="no"
                      - mkdir -p subdir
                      - cd subdir
                  build:
                    commands:
                      - |
                        false 2> /dev/null
                        status=$?
                        if [ $status -ne 0 ]; then MIGRATION="no"; else MIGRATION="yes"; fi
                      - if [ $ENABLE_EXTERNAL_PIPELINE_ACCOUNT = "yes" ]; then echo external; fi
                      - test "$ENABLE_EXTERNAL_PIPELINE_ACCOUNT" = "no"
                      - test "$MIGRATION" = "no"
                      - test "$(pwd)" = "/codebuild/output/src/src/subdir"
                  post_build:
                    commands:
                      - test "$(pwd)" = "/codebuild/output/src/src/subdir"
                """);

        Map<String, Object> build = awaitBuild(buildId);

        assertEquals("SUCCEEDED", build.get("buildStatus"));
        for (String phaseType : List.of("INSTALL", "PRE_BUILD", "BUILD", "POST_BUILD")) {
            Map<String, Object> phase = phase(build, phaseType);
            assertEquals("SUCCEEDED", phase.get("phaseStatus"), phaseType);
            assertNotNull(phase.get("startTime"), phaseType);
            assertNotNull(phase.get("endTime"), phaseType);
            assertNotNull(phase.get("durationInSeconds"), phaseType);
        }
    }

    @Test
    void failingBuildPhaseReportsContextAndStillRunsPostBuild() {
        String project = "build-fail-" + Long.toString(System.nanoTime(), 36);
        createProject(project, BASH_IMAGE);
        String buildId = startBuild(project, """
                version: 0.2
                phases:
                  pre_build:
                    commands:
                      - PIPELINE_STAGE="deploy"
                  build:
                    commands:
                      - echo before-failure
                      - set -e && false
                      - echo never-reached
                  post_build:
                    commands:
                      - test "$PIPELINE_STAGE" = "deploy"
                      - sleep 2
                """);

        Map<String, Object> build = awaitBuild(buildId);

        assertEquals("FAILED", build.get("buildStatus"));

        Map<String, Object> buildPhase = phase(build, "BUILD");
        assertEquals("FAILED", buildPhase.get("phaseStatus"));
        Map<String, String> context = firstContext(buildPhase);
        assertEquals("COMMAND_EXECUTION_ERROR", context.get("statusCode"));
        assertTrue(context.get("message").contains("Exit code 1"), context.get("message"));
        assertTrue(context.get("message").contains("before-failure"), context.get("message"));
        assertFalse(context.get("message").contains("never-reached"), context.get("message"));

        Map<String, Object> postBuild = phase(build, "POST_BUILD");
        assertEquals("SUCCEEDED", postBuild.get("phaseStatus"));
        assertTrue(((Number) postBuild.get("durationInSeconds")).longValue() >= 1,
                "POST_BUILD should have actually run, not been skipped");

        assertEquals("FAILED", phase(build, "COMPLETED").get("phaseStatus"));
    }

    @Test
    void failedInstallSkipsPreBuildAndBuildButRunsPostBuild() {
        String project = "install-fail-" + Long.toString(System.nanoTime(), 36);
        createProject(project, BASH_IMAGE);
        String buildId = startBuild(project, """
                version: 0.2
                phases:
                  install:
                    commands:
                      - INSTALL_MARKER="set"
                      - false
                  build:
                    commands:
                      - echo marker-build
                  post_build:
                    commands:
                      - test "$INSTALL_MARKER" = "set"
                      - sleep 2
                """);

        Map<String, Object> build = awaitBuild(buildId);

        assertEquals("FAILED", build.get("buildStatus"));
        assertEquals("FAILED", phase(build, "INSTALL").get("phaseStatus"));

        for (String skipped : List.of("PRE_BUILD", "BUILD")) {
            Map<String, Object> phase = phase(build, skipped);
            assertEquals("SUCCEEDED", phase.get("phaseStatus"), skipped);
            assertEquals(0L, ((Number) phase.get("durationInSeconds")).longValue(), skipped);
        }

        Map<String, Object> postBuild = phase(build, "POST_BUILD");
        assertEquals("SUCCEEDED", postBuild.get("phaseStatus"));
        assertTrue(((Number) postBuild.get("durationInSeconds")).longValue() >= 1,
                "POST_BUILD should have actually run, not been skipped");
    }

    @Test
    void shFallbackStillSharesShellStateWhenBashIsAbsent() {
        String project = "sh-fallback-" + Long.toString(System.nanoTime(), 36);
        createProject(project, SH_IMAGE);
        String buildId = startBuild(project, """
                version: 0.2
                phases:
                  pre_build:
                    commands:
                      - ENABLE_EXTERNAL_PIPELINE_ACCOUNT="no"
                      - mkdir -p subdir
                      - cd subdir
                  build:
                    commands:
                      - test "$ENABLE_EXTERNAL_PIPELINE_ACCOUNT" = "no"
                      - test "$(pwd)" = "/codebuild/output/src/src/subdir"
                """);

        Map<String, Object> build = awaitBuild(buildId);

        assertEquals("SUCCEEDED", build.get("buildStatus"));
        assertEquals("SUCCEEDED", phase(build, "BUILD").get("phaseStatus"));
    }

    @Test
    void secondarySourcesAreStagedIntoTheirOwnDirectories() throws Exception {
        String project = "secondary-src-" + Long.toString(System.nanoTime(), 36);
        createProject(project, BASH_IMAGE);
        String bucket = "secondary-src-" + Long.toString(System.nanoTime(), 36);
        given().when().put("/" + bucket).then().statusCode(200);
        given()
            .contentType("application/octet-stream")
            .body(secondarySourceZip())
        .when()
            .put("/" + bucket + "/Config.zip")
        .then()
            .statusCode(200);

        String buildId = given()
            .header("X-Amz-Target", "CodeBuild_20161006.StartBuild")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + project + "\", \"buildspecOverride\": \""
                    + jsonEscape("""
                        version: 0.2
                        phases:
                          build:
                            commands:
                              - test "$CODEBUILD_SRC_DIR" = "/codebuild/output/src/src"
                              - test "$CODEBUILD_SRC_DIR_Config" = "/codebuild/output/src-Config/src"
                              - test "$(cat "$CODEBUILD_SRC_DIR_Config/config/settings.txt")" = "cfg"
                              - test -x "$CODEBUILD_SRC_DIR_Config/scripts/run.sh"
                              - "$CODEBUILD_SRC_DIR_Config/scripts/run.sh"
                        """)
                    + "\", \"secondarySourcesOverride\": [{\"type\": \"S3\", \"location\": \""
                    + bucket + "/Config.zip\", \"sourceIdentifier\": \"Config\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("build.id");

        Map<String, Object> build = awaitBuild(buildId);

        assertEquals("SUCCEEDED", build.get("buildStatus"));
    }

    @Test
    void missingParameterStoreEnvVarFailsProvisioning() {
        String project = "param-missing-" + Long.toString(System.nanoTime(), 36);
        createProject(project, BASH_IMAGE);
        String buildId = given()
            .header("X-Amz-Target", "CodeBuild_20161006.StartBuild")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + project + "\", \"buildspecOverride\": \""
                    + jsonEscape("""
                        version: 0.2
                        phases:
                          build:
                            commands:
                              - echo never-reached
                        """)
                    + "\", \"environmentVariablesOverride\": [{\"name\": \"ACCELERATOR_PIPELINE_VERSION\", "
                    + "\"value\": \"/floci/does-not-exist\", \"type\": \"PARAMETER_STORE\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("build.id");

        Map<String, Object> build = awaitBuild(buildId);

        assertEquals("FAILED", build.get("buildStatus"));
        Map<String, Object> provisioning = phase(build, "PROVISIONING");
        assertEquals("FAILED", provisioning.get("phaseStatus"));
        Map<String, String> context = firstContext(provisioning);
        assertTrue(context.get("message").contains("ACCELERATOR_PIPELINE_VERSION"), context.get("message"));
        assertTrue(context.get("message").contains("/floci/does-not-exist"), context.get("message"));
    }

    private static byte[] secondarySourceZip() throws Exception {
        try (var baos = new java.io.ByteArrayOutputStream()) {
            try (var zos = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(baos)) {
                var settings = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("config/settings.txt");
                zos.putArchiveEntry(settings);
                zos.write("cfg".getBytes());
                zos.closeArchiveEntry();
                var script = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("scripts/run.sh");
                script.setUnixMode(0755);
                zos.putArchiveEntry(script);
                zos.write("#!/bin/sh\nexit 0\n".getBytes());
                zos.closeArchiveEntry();
            }
            return baos.toByteArray();
        }
    }

    private void createProject(String name, String image) {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "%s",
                    "source": {"type": "NO_SOURCE"},
                    "artifacts": {"type": "NO_ARTIFACTS"},
                    "environment": {
                        "type": "LINUX_CONTAINER",
                        "image": "%s",
                        "computeType": "BUILD_GENERAL1_SMALL"
                    },
                    "serviceRole": "arn:aws:iam::000000000000:role/codebuild-role"
                }
                """.formatted(name, image))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private String startBuild(String projectName, String buildspec) {
        return given()
            .header("X-Amz-Target", "CodeBuild_20161006.StartBuild")
            .contentType(CONTENT_TYPE)
            .body("{\"projectName\": \"" + projectName + "\", \"buildspecOverride\": \""
                    + jsonEscape(buildspec) + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("build.id");
    }

    private Map<String, Object> awaitBuild(String buildId) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> build = given()
                .header("X-Amz-Target", "CodeBuild_20161006.BatchGetBuilds")
                .contentType(CONTENT_TYPE)
                .body("{\"ids\": [\"" + buildId + "\"]}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("builds[0]");
            if (build != null && Boolean.TRUE.equals(build.get("buildComplete"))) {
                return build;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for build " + buildId, e);
            }
        }
        throw new AssertionError("Build " + buildId + " did not complete within 120s");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> phase(Map<String, Object> build, String phaseType) {
        List<Map<String, Object>> phases = (List<Map<String, Object>>) build.get("phases");
        return phases.stream()
                .filter(p -> phaseType.equals(p.get("phaseType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing phase " + phaseType + " in " + phases));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> firstContext(Map<String, Object> phase) {
        List<Map<String, String>> contexts = (List<Map<String, String>>) phase.get("contexts");
        assertNotNull(contexts, "Expected failure contexts on phase " + phase.get("phaseType"));
        return contexts.get(0);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
