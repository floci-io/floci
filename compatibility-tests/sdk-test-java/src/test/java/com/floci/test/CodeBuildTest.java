package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.codebuild.model.ArtifactsType;
import software.amazon.awssdk.services.codebuild.model.BatchGetProjectsResponse;
import software.amazon.awssdk.services.codebuild.model.ComputeType;
import software.amazon.awssdk.services.codebuild.model.CreateProjectResponse;
import software.amazon.awssdk.services.codebuild.model.CreateReportGroupResponse;
import software.amazon.awssdk.services.codebuild.model.DeleteReportGroupRequest;
import software.amazon.awssdk.services.codebuild.model.EnvironmentType;
import software.amazon.awssdk.services.codebuild.model.ImportSourceCredentialsResponse;
import software.amazon.awssdk.services.codebuild.model.ListCuratedEnvironmentImagesResponse;
import software.amazon.awssdk.services.codebuild.model.ListProjectsResponse;
import software.amazon.awssdk.services.codebuild.model.ListReportGroupsResponse;
import software.amazon.awssdk.services.codebuild.model.ListSourceCredentialsResponse;
import software.amazon.awssdk.services.codebuild.model.ProjectArtifacts;
import software.amazon.awssdk.services.codebuild.model.ProjectEnvironment;
import software.amazon.awssdk.services.codebuild.model.ProjectSource;
import software.amazon.awssdk.services.codebuild.model.ReportExportConfig;
import software.amazon.awssdk.services.codebuild.model.ReportExportConfigType;
import software.amazon.awssdk.services.codebuild.model.ReportType;
import software.amazon.awssdk.services.codebuild.model.AuthType;
import software.amazon.awssdk.services.codebuild.model.ServerType;
import software.amazon.awssdk.services.codebuild.model.SourceType;
import software.amazon.awssdk.services.codebuild.model.BatchGetBuildsResponse;
import software.amazon.awssdk.services.codebuild.model.Build;
import software.amazon.awssdk.services.codebuild.model.ListBuildsForProjectResponse;
import software.amazon.awssdk.services.codebuild.model.ListBuildsResponse;
import software.amazon.awssdk.services.codebuild.model.RetryBuildResponse;
import software.amazon.awssdk.services.codebuild.model.StartBuildResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.codebuild.model.Tag;
import software.amazon.awssdk.services.codebuild.model.UpdateProjectResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeBuildTest {

    private static final String CODEBUILD_TEST_IMAGE =
            "public.ecr.aws/docker/library/busybox@sha256:fd8d9aa63ba2f0982b5304e1ee8d3b90a210bc1ffb5314d980eb6962f1a9715d";

    static CodeBuildClient codebuild;
    static String reportGroupArn;
    static String sourceCredentialsArn;
    static String buildId;

    @BeforeAll
    static void setup() {
        codebuild = TestFixtures.codeBuildClient();
    }

    @AfterAll
    static void teardown() {
        codebuild.close();
    }

    @Test
    @Order(1)
    void createProject() {
        CreateProjectResponse resp = codebuild.createProject(r -> r
                .name("sdk-test-project")
                .description("SDK test project")
                .source(ProjectSource.builder()
                        .type(SourceType.S3)
                        .location("my-bucket/source.zip")
                        .build())
                .artifacts(ProjectArtifacts.builder()
                        .type(ArtifactsType.NO_ARTIFACTS)
                        .build())
                .environment(ProjectEnvironment.builder()
                        .type(EnvironmentType.LINUX_CONTAINER)
                        .image("aws/codebuild/standard:7.0")
                        .computeType(ComputeType.BUILD_GENERAL1_SMALL)
                        .build())
                .serviceRole("arn:aws:iam::000000000000:role/codebuild-role")
                .tags(Tag.builder().key("env").value("test").build()));

        assertThat(resp.project().name()).isEqualTo("sdk-test-project");
        assertThat(resp.project().arn()).contains(":project/sdk-test-project");
        assertThat(resp.project().description()).isEqualTo("SDK test project");
        assertThat(resp.project().timeoutInMinutes()).isEqualTo(60);
    }

    @Test
    @Order(2)
    void batchGetProjects() {
        BatchGetProjectsResponse resp = codebuild.batchGetProjects(r -> r
                .names("sdk-test-project", "nonexistent"));

        assertThat(resp.projects()).hasSize(1);
        assertThat(resp.projects().get(0).name()).isEqualTo("sdk-test-project");
        assertThat(resp.projectsNotFound()).containsExactly("nonexistent");
    }

    @Test
    @Order(3)
    void listProjects() {
        ListProjectsResponse resp = codebuild.listProjects(r -> r.build());
        assertThat(resp.projects()).contains("sdk-test-project");
    }

    @Test
    @Order(4)
    void updateProject() {
        UpdateProjectResponse resp = codebuild.updateProject(r -> r
                .name("sdk-test-project")
                .description("Updated by SDK test")
                .timeoutInMinutes(120));

        assertThat(resp.project().description()).isEqualTo("Updated by SDK test");
        assertThat(resp.project().timeoutInMinutes()).isEqualTo(120);
    }

    @Test
    @Order(5)
    void createReportGroup() {
        CreateReportGroupResponse resp = codebuild.createReportGroup(r -> r
                .name("sdk-report-group")
                .type(ReportType.TEST)
                .exportConfig(ReportExportConfig.builder()
                        .exportConfigType(ReportExportConfigType.NO_EXPORT)
                        .build()));

        assertThat(resp.reportGroup().name()).isEqualTo("sdk-report-group");
        assertThat(resp.reportGroup().arn()).contains(":report-group/sdk-report-group");
        assertThat(resp.reportGroup().status().toString()).isEqualTo("ACTIVE");
        reportGroupArn = resp.reportGroup().arn();
    }

    @Test
    @Order(6)
    void listReportGroups() {
        ListReportGroupsResponse resp = codebuild.listReportGroups(r -> r.build());
        assertThat(resp.reportGroups()).contains(reportGroupArn);
    }

    @Test
    @Order(7)
    void importSourceCredentials() {
        ImportSourceCredentialsResponse resp = codebuild.importSourceCredentials(r -> r
                .token("ghp_test_token_sdk")
                .serverType(ServerType.GITHUB)
                .authType(AuthType.PERSONAL_ACCESS_TOKEN));

        assertThat(resp.arn()).contains(":token/github-");
        sourceCredentialsArn = resp.arn();
    }

    @Test
    @Order(8)
    void listSourceCredentials() {
        ListSourceCredentialsResponse resp = codebuild.listSourceCredentials(r -> r.build());
        assertThat(resp.sourceCredentialsInfos()).isNotEmpty();
        assertThat(resp.sourceCredentialsInfos().get(0).serverType()).isEqualTo(ServerType.GITHUB);
        assertThat(resp.sourceCredentialsInfos().get(0).authType()).isEqualTo(AuthType.PERSONAL_ACCESS_TOKEN);
    }

    @Test
    @Order(9)
    void listCuratedEnvironmentImages() {
        ListCuratedEnvironmentImagesResponse resp = codebuild.listCuratedEnvironmentImages(r -> r.build());
        assertThat(resp.platforms()).isNotEmpty();
        assertThat(resp.platforms().get(0).platformAsString()).isNotBlank();
    }

    @Test
    @Order(10)
    void deleteSourceCredentials() {
        codebuild.deleteSourceCredentials(r -> r.arn(sourceCredentialsArn));

        ListSourceCredentialsResponse after = codebuild.listSourceCredentials(r -> r.build());
        assertThat(after.sourceCredentialsInfos()).isEmpty();
    }

    @Test
    @Order(11)
    void deleteReportGroup() {
        codebuild.deleteReportGroup(DeleteReportGroupRequest.builder()
                .arn(reportGroupArn)
                .build());
    }

    @Test
    @Order(12)
    void deleteProject() {
        codebuild.deleteProject(r -> r.name("sdk-test-project"));

        ListProjectsResponse after = codebuild.listProjects(r -> r.build());
        assertThat(after.projects()).doesNotContain("sdk-test-project");
    }

    // ---- Phase 2: Real Build Execution ----

    @Test
    @Order(20)
    void setupBuildProject() {
        codebuild.createProject(r -> r
                .name("build-exec-project")
                .source(ProjectSource.builder()
                        .type(SourceType.NO_SOURCE)
                        .build())
                .artifacts(ProjectArtifacts.builder()
                        .type(ArtifactsType.NO_ARTIFACTS)
                        .build())
                .environment(ProjectEnvironment.builder()
                        .type(EnvironmentType.LINUX_CONTAINER)
                        .image(CODEBUILD_TEST_IMAGE)
                        .computeType(ComputeType.BUILD_GENERAL1_SMALL)
                        .build())
                .serviceRole("arn:aws:iam::000000000000:role/codebuild-role"));
    }

    @Test
    @Order(21)
    void startBuild_noSource() {
        String buildspec = "version: 0.2\nphases:\n  build:\n    commands:\n      - echo hello-from-codebuild\n";

        StartBuildResponse resp = codebuild.startBuild(r -> r
                .projectName("build-exec-project")
                .buildspecOverride(buildspec));

        assertThat(resp.build().id()).contains("build-exec-project:");
        assertThat(resp.build().arn()).contains(":build/build-exec-project:");
        assertThat(resp.build().buildStatusAsString()).isEqualTo("IN_PROGRESS");
        assertThat(resp.build().buildComplete()).isFalse();
        buildId = resp.build().id();
    }

    @Test
    @Order(22)
    void batchGetBuilds_eventuallySucceeds() throws InterruptedException {
        assertThat(buildId).isNotNull();

        Build build = null;
        for (int i = 0; i < 30; i++) {
            BatchGetBuildsResponse resp = codebuild.batchGetBuilds(r -> r.ids(buildId));
            assertThat(resp.builds()).hasSize(1);
            build = resp.builds().get(0);
            if (Boolean.TRUE.equals(build.buildComplete())) {
                break;
            }
            Thread.sleep(2000);
        }

        assertThat(build).isNotNull();
        assertThat(build.buildComplete()).isTrue();
        assertThat(build.buildStatusAsString()).isEqualTo("SUCCEEDED");
        assertThat(build.currentPhase()).isEqualTo("COMPLETED");
        assertThat(build.phases()).isNotEmpty();
    }

    @Test
    @Order(23)
    void listBuilds_containsBuildId() {
        assertThat(buildId).isNotNull();
        ListBuildsResponse resp = codebuild.listBuilds(r -> r.build());
        assertThat(resp.ids()).contains(buildId);
    }

    @Test
    @Order(24)
    void listBuildsForProject() {
        ListBuildsForProjectResponse resp = codebuild.listBuildsForProject(r -> r
                .projectName("build-exec-project"));
        assertThat(resp.ids()).contains(buildId);
    }

    @Test
    @Order(25)
    void retryBuild() {
        assertThat(buildId).isNotNull();
        RetryBuildResponse resp = codebuild.retryBuild(r -> r.id(buildId));
        assertThat(resp.build().id()).contains("build-exec-project:");
        assertThat(resp.build().id()).isNotEqualTo(buildId);
        assertThat(resp.build().buildStatusAsString()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @Order(26)
    void stopBuild() throws InterruptedException {
        // Start a new long-running build, then stop it
        String longBuildspec = "version: 0.2\nphases:\n  build:\n    commands:\n      - sleep 60\n";
        StartBuildResponse startResp = codebuild.startBuild(r -> r
                .projectName("build-exec-project")
                .buildspecOverride(longBuildspec));
        String longBuildId = startResp.build().id();

        // Give it a moment to start the container
        Thread.sleep(3000);

        codebuild.stopBuild(r -> r.id(longBuildId));

        // Poll until stopped
        Build build = null;
        for (int i = 0; i < 20; i++) {
            BatchGetBuildsResponse resp = codebuild.batchGetBuilds(r -> r.ids(longBuildId));
            build = resp.builds().get(0);
            if (Boolean.TRUE.equals(build.buildComplete())) {
                break;
            }
            Thread.sleep(1000);
        }

        assertThat(build).isNotNull();
        assertThat(build.buildComplete()).isTrue();
        assertThat(build.buildStatusAsString()).isEqualTo("STOPPED");
    }

    @Test
    @Order(27)
    void deleteBuildProject() {
        codebuild.deleteProject(r -> r.name("build-exec-project"));
    }

    // ---- OS demo: list OS family + directory tree, upload to S3 ----

    @Test
    @Order(30)
    void createOsBucket() {
        S3Client s3 = TestFixtures.s3Client();
        s3.createBucket(r -> r.bucket("os-bucket"));
        s3.close();
    }

    @Test
    @Order(31)
    void setupOsDemoProject() {
        codebuild.createProject(r -> r
                .name("os-demo-project")
                .source(ProjectSource.builder()
                        .type(SourceType.NO_SOURCE)
                        .build())
                .artifacts(ProjectArtifacts.builder()
                        .type(ArtifactsType.S3)
                        .location("os-bucket")
                        .packaging("NONE")
                        .build())
                .environment(ProjectEnvironment.builder()
                        .type(EnvironmentType.LINUX_CONTAINER)
                        .image(CODEBUILD_TEST_IMAGE)
                        .computeType(ComputeType.BUILD_GENERAL1_SMALL)
                        .build())
                .serviceRole("arn:aws:iam::000000000000:role/codebuild-role"));
    }

    @Test
    @Order(32)
    void startOsDemoBuild() {
        String buildspec = String.join("\n",
                "version: 0.2",
                "phases:",
                "  build:",
                "    commands:",
                "      - echo '=== OS Family ===' > command-output.txt",
                "      - uname -a >> command-output.txt",
                "      - echo '' >> command-output.txt",
                "      - echo '=== Directory Tree (depth 3) ===' >> command-output.txt",
                "      - find / -maxdepth 3 -type d 2>/dev/null >> command-output.txt",
                "artifacts:",
                "  files:",
                "    - command-output.txt"
        );

        StartBuildResponse resp = codebuild.startBuild(r -> r
                .projectName("os-demo-project")
                .buildspecOverride(buildspec));

        buildId = resp.build().id();
        assertThat(buildId).contains("os-demo-project:");
    }

    @Test
    @Order(33)
    void osDemoBuild_eventuallySucceeds() throws InterruptedException {
        assertThat(buildId).isNotNull();

        Build build = null;
        for (int i = 0; i < 30; i++) {
            BatchGetBuildsResponse resp = codebuild.batchGetBuilds(r -> r.ids(buildId));
            build = resp.builds().get(0);
            if (Boolean.TRUE.equals(build.buildComplete())) {
                break;
            }
            Thread.sleep(2000);
        }

        assertThat(build).isNotNull();
        assertThat(build.buildComplete()).isTrue();
        assertThat(build.buildStatusAsString()).isEqualTo("SUCCEEDED");
    }

    @Test
    @Order(34)
    void osDemoBuild_outputUploadedToS3() {
        S3Client s3 = TestFixtures.s3Client();

        byte[] data = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("os-bucket")
                .key("command-output.txt")
                .build()).asByteArray();

        String content = new String(data);
        System.out.println("=== command-output.txt from os-bucket ===");
        System.out.println(content);

        assertThat(content).contains("Linux");
        assertThat(content).contains("/usr");

        s3.close();
    }

    @Test
    @Order(35)
    void cleanupOsDemo() {
        codebuild.deleteProject(r -> r.name("os-demo-project"));
    }
}
