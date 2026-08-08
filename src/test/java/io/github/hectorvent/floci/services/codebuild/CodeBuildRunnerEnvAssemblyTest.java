package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.codebuild.BuildspecParser.ParsedArtifacts;
import io.github.hectorvent.floci.services.codebuild.BuildspecParser.ParsedBuildspec;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the runner's container environment assembly: PARAMETER_STORE-typed
 * environment variables resolve through SSM (a missing parameter fails the assembly,
 * which the runner surfaces as a PROVISIONING failure), later variable lists win over
 * earlier ones, and each secondary source gets its CODEBUILD_SRC_DIR_&lt;identifier&gt;.
 */
class CodeBuildRunnerEnvAssemblyTest {

    private static final String REGION = "us-east-1";

    private SsmService ssmService;
    private EmulatorConfig config;
    private EmulatorConfig.DnsConfig dnsConfig;
    private EmulatorConfig.TlsConfig tlsConfig;
    private ContainerDetector containerDetector;
    private CodeBuildRunner runner;

    @BeforeEach
    void setUp() {
        ssmService = mock(SsmService.class);
        config = mock(EmulatorConfig.class);
        dnsConfig = mock(EmulatorConfig.DnsConfig.class);
        tlsConfig = mock(EmulatorConfig.TlsConfig.class);
        containerDetector = mock(ContainerDetector.class);
        when(config.dns()).thenReturn(dnsConfig);
        when(config.tls()).thenReturn(tlsConfig);
        when(config.port()).thenReturn(4566);
        when(config.hostname()).thenReturn(Optional.empty());
        runner = new CodeBuildRunner(mock(DockerClient.class), mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class), mock(ContainerLogStreamer.class),
                mock(S3Service.class), ssmService, mock(SecretsManagerService.class),
                config, containerDetector, mock(RegionResolver.class));
    }

    private static ParsedBuildspec emptyBuildspec() {
        return new ParsedBuildspec(Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(),
                new ParsedArtifacts("NO_ARTIFACTS", List.of(), null, false, null, null));
    }

    private static Project project(List<Map<String, String>> environmentVariables) {
        Project project = new Project();
        project.setName("proj");
        ProjectEnvironment environment = new ProjectEnvironment();
        environment.setImage("public.ecr.aws/docker/library/alpine:latest");
        environment.setEnvironmentVariables(environmentVariables);
        project.setEnvironment(environment);
        return project;
    }

    private static Build build() {
        Build build = new Build();
        build.setId("proj:1");
        build.setArn("arn:aws:codebuild:us-east-1:000000000000:build/proj:1");
        build.setBuildNumber(1L);
        return build;
    }

    private List<String> envList(Build build, Project project) {
        return runner.buildEnvList(REGION, build, project, emptyBuildspec(), "log-stream");
    }

    @Test
    void parameterStoreTypedVariablesResolveFromSsm() {
        when(ssmService.getParameter("/accelerator/version", REGION))
                .thenReturn(new Parameter("/accelerator/version", "1.12.1", "String"));
        Project project = project(List.of(Map.of(
                "name", "ACCELERATOR_PIPELINE_VERSION",
                "value", "/accelerator/version",
                "type", "PARAMETER_STORE")));

        List<String> env = envList(build(), project);

        assertTrue(env.contains("ACCELERATOR_PIPELINE_VERSION=1.12.1"), env.toString());
    }

    @Test
    void missingParameterStoreParameterFailsAssembly() {
        when(ssmService.getParameter("/accelerator/missing", REGION))
                .thenThrow(new AwsException("ParameterNotFound",
                        "Parameter /accelerator/missing not found.", 400));
        Project project = project(List.of(Map.of(
                "name", "ACCELERATOR_PIPELINE_VERSION",
                "value", "/accelerator/missing",
                "type", "PARAMETER_STORE")));

        AwsException error = assertThrows(AwsException.class, () -> envList(build(), project));

        assertTrue(error.getMessage().contains("ACCELERATOR_PIPELINE_VERSION"), error.getMessage());
        assertTrue(error.getMessage().contains("/accelerator/missing"), error.getMessage());
    }

    @Test
    void buildLevelVariablesWinOverProjectVariables() {
        when(ssmService.getParameter("/pipeline/stage", REGION))
                .thenReturn(new Parameter("/pipeline/stage", "prepare", "String"));
        Project project = project(List.of(
                Map.of("name", "ACCELERATOR_STAGE", "value", "project-level"),
                Map.of("name", "UNTOUCHED", "value", "kept")));
        Build build = build();
        ProjectEnvironment overrides = new ProjectEnvironment();
        overrides.setEnvironmentVariables(List.of(Map.of(
                "name", "ACCELERATOR_STAGE", "value", "/pipeline/stage", "type", "PARAMETER_STORE")));
        build.setEnvironment(overrides);

        List<String> env = envList(build, project);

        assertTrue(env.contains("ACCELERATOR_STAGE=prepare"), env.toString());
        assertTrue(env.contains("UNTOUCHED=kept"), env.toString());
        assertTrue(env.stream().noneMatch("ACCELERATOR_STAGE=project-level"::equals), env.toString());
    }

    // ── endpoint injection scheme ─────────────────────────────────────────────

    @Test
    void endpointUrlStaysHttpByDefault() {
        List<String> env = envList(build(), project(null));

        assertTrue(env.contains("AWS_ENDPOINT_URL=http://host.docker.internal:4566"), env.toString());
    }

    // Clients like the CDK toolkit pass a shared https.Agent into their SDK
    // httpOptions and Node rejects http: URLs on an https.Agent — the injected
    // endpoint must be https when the spoofed endpoints are served with TLS.
    @Test
    void endpointUrlSwitchesToHttpsWhenSpoofingAndTlsBothEnabled() {
        when(dnsConfig.spoofAwsEndpoints()).thenReturn(true);
        when(tlsConfig.enabled()).thenReturn(true);

        List<String> env = envList(build(), project(null));

        assertTrue(env.contains("AWS_ENDPOINT_URL=https://host.docker.internal:4566"), env.toString());
    }

    @Test
    void endpointUrlUsesHttpsWithDnsSuffixWhenRunningInContainer() {
        when(dnsConfig.spoofAwsEndpoints()).thenReturn(true);
        when(tlsConfig.enabled()).thenReturn(true);
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        List<String> env = envList(build(), project(null));

        assertTrue(env.contains("AWS_ENDPOINT_URL=https://localhost.floci.io:4566"), env.toString());
    }

    @Test
    void endpointUrlKeepsHttpWhenOnlyOneFlagIsEnabled() {
        when(dnsConfig.spoofAwsEndpoints()).thenReturn(true);

        assertTrue(envList(build(), project(null))
                .contains("AWS_ENDPOINT_URL=http://host.docker.internal:4566"));

        when(dnsConfig.spoofAwsEndpoints()).thenReturn(false);
        when(tlsConfig.enabled()).thenReturn(true);

        assertTrue(envList(build(), project(null))
                .contains("AWS_ENDPOINT_URL=http://host.docker.internal:4566"));
    }

    @Test
    void secondarySourcesGetTheirOwnSrcDirVariables() {
        Build build = build();
        ProjectSource config = new ProjectSource();
        config.setType("S3");
        config.setLocation("bucket/codepipeline/exec-1/Config.zip");
        config.setSourceIdentifier("Config");
        build.setSecondarySources(List.of(config));

        List<String> env = envList(build, project(null));

        assertTrue(env.contains("CODEBUILD_SRC_DIR=/codebuild/output/src/src"), env.toString());
        assertTrue(env.contains("CODEBUILD_SRC_DIR_Config=/codebuild/output/src-Config/src"), env.toString());
        assertEquals("/codebuild/output/src-Config/src", CodeBuildRunner.secondarySourceDir("Config"));
    }
}
