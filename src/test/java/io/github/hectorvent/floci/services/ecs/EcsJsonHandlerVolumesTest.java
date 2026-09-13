package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.container.HostVolumePolicy;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RegisterTaskDefinition JSON wire path in {@link EcsJsonHandler}:
 * task-level {@code volumes} and per-container {@code mountPoints} must be parsed from the
 * request, stored on the task definition, and serialized back in the response (round-trip
 * fidelity, faithful to the EC2-launch-type ECS shape {@code volumes[].host.sourcePath}).
 *
 * <p>{@link EcsService} is mocked to echo the parsed container definitions back inside a
 * task definition, so the test exercises parse + serialize without any Docker/Quarkus context.
 */
class EcsJsonHandlerVolumesTest {

    private EcsService service;
    private ObjectMapper objectMapper;
    private EmulatorConfig config;
    private EcsJsonHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = mock(EcsService.class);
        // Echo the parsed container definitions (arg index 1) back inside a task definition.
        when(service.registerTaskDefinition(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    TaskDefinition td = new TaskDefinition();
                    td.setFamily(inv.getArgument(0));
                    td.setRevision(1);
                    td.setStatus("ACTIVE");
                    td.setContainerDefinitions(inv.getArgument(1, List.class));
                    return td;
                });

        // Deep-stubbed: unstubbed leaf methods return Optional.empty()/false, the same
        // fail-closed defaults as production, so host-volume-roots is empty and
        // allow-unsafe-host-volumes is false unless a test stubs otherwise. That means any
        // test registering a host-volume sourcePath must explicitly opt in (a root or the
        // unsafe flag) or expect the default rejection.
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        handler = new EcsJsonHandler(service, objectMapper, new HostVolumePolicy(config));
    }

    private static String registerRequestWithHostVolume(String sourcePath) {
        return """
                {
                  "family": "host-volume-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "mountPoints": [
                        {"sourceVolume": "vol", "containerPath": "/app/data", "readOnly": false}
                      ]
                    }
                  ],
                  "volumes": [
                    {"name": "vol", "host": {"sourcePath": "%s"}}
                  ]
                }
                """.formatted(sourcePath.replace("\\", "\\\\"));
    }

    @Test
    void registerTaskDefinitionRoundTripsVolumesAndMountPoints() throws Exception {
        // This test is about JSON round-trip fidelity, not host-volume safety, so opt out of
        // the fail-closed default explicitly.
        when(config.services().ecs().allowUnsafeHostVolumes()).thenReturn(true);
        String requestJson = """
                {
                  "family": "volumes-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "mountPoints": [
                        {"sourceVolume": "config-vol", "containerPath": "/app/config.yml", "readOnly": true},
                        {"sourceVolume": "aws-vol", "containerPath": "/root/.aws", "readOnly": false}
                      ]
                    }
                  ],
                  "volumes": [
                    {"name": "config-vol", "host": {"sourcePath": "/host/abs/config.yml"}},
                    {"name": "aws-vol", "host": {"sourcePath": "/host/abs/.aws"}}
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        // Task-level volumes round-trip with the EC2 host.sourcePath shape.
        JsonNode volumes = td.path("volumes");
        assertTrue(volumes.isArray() && volumes.size() == 2, "two volumes expected");
        assertEquals("config-vol", volumes.get(0).path("name").asText());
        assertEquals("/host/abs/config.yml", volumes.get(0).path("host").path("sourcePath").asText());
        assertEquals("aws-vol", volumes.get(1).path("name").asText());
        assertEquals("/host/abs/.aws", volumes.get(1).path("host").path("sourcePath").asText());

        // Per-container mountPoints round-trip with sourceVolume/containerPath/readOnly.
        JsonNode mps = td.path("containerDefinitions").get(0).path("mountPoints");
        assertTrue(mps.isArray() && mps.size() == 2, "two mountPoints expected");
        assertEquals("config-vol", mps.get(0).path("sourceVolume").asText());
        assertEquals("/app/config.yml", mps.get(0).path("containerPath").asText());
        assertTrue(mps.get(0).path("readOnly").asBoolean(), "config mount is read-only");
        assertEquals("aws-vol", mps.get(1).path("sourceVolume").asText());
        assertEquals("/root/.aws", mps.get(1).path("containerPath").asText());
        assertFalse(mps.get(1).path("readOnly").asBoolean(), "aws mount is read-write");
    }

    @Test
    void registerTaskDefinitionRoundTripsEfsVolumeConfiguration() throws Exception {
        String requestJson = """
                {
                  "family": "efs-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "mountPoints": [
                        {"sourceVolume": "customer-data", "containerPath": "/mnt/efs", "readOnly": false}
                      ]
                    }
                  ],
                  "volumes": [
                    {
                      "name": "customer-data",
                      "efsVolumeConfiguration": {
                        "fileSystemId": "fs-0123456789abcdef0",
                        "rootDirectory": "/",
                        "transitEncryption": "ENABLED",
                        "transitEncryptionPort": 2999,
                        "authorizationConfig": {"accessPointId": "fsap-0abc", "iam": "ENABLED"}
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");

        // The efsVolumeConfiguration round-trips faithfully (no drift on Terraform reads).
        JsonNode vol = td.path("volumes").get(0);
        assertEquals("customer-data", vol.path("name").asText());
        assertTrue(vol.path("host").isMissingNode(), "EFS volume must not carry a host shape");
        JsonNode efs = vol.path("efsVolumeConfiguration");
        assertEquals("fs-0123456789abcdef0", efs.path("fileSystemId").asText());
        assertEquals("/", efs.path("rootDirectory").asText());
        assertEquals("ENABLED", efs.path("transitEncryption").asText());
        assertEquals(2999, efs.path("transitEncryptionPort").asInt());
        assertEquals("fsap-0abc", efs.path("authorizationConfig").path("accessPointId").asText());
        assertEquals("ENABLED", efs.path("authorizationConfig").path("iam").asText());
    }

    @Test
    void registerTaskDefinitionRejectsAccessPointWithNonDefaultRootDirectory() throws Exception {
        String requestJson = """
                {
                  "family": "efs-invalid-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "mountPoints": [
                        {"sourceVolume": "customer-data", "containerPath": "/mnt/efs", "readOnly": false}
                      ]
                    }
                  ],
                  "volumes": [
                    {
                      "name": "customer-data",
                      "efsVolumeConfiguration": {
                        "fileSystemId": "fs-0123456789abcdef0",
                        "rootDirectory": "/dps",
                        "authorizationConfig": {"accessPointId": "fsap-0abc"}
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void registerTaskDefinitionAllowsAccessPointWithOmittedRootDirectory() throws Exception {
        String requestJson = """
                {
                  "family": "efs-valid-access-point-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "mountPoints": [
                        {"sourceVolume": "customer-data", "containerPath": "/mnt/efs", "readOnly": false}
                      ]
                    }
                  ],
                  "volumes": [
                    {
                      "name": "customer-data",
                      "efsVolumeConfiguration": {
                        "fileSystemId": "fs-0123456789abcdef0",
                        "authorizationConfig": {"accessPointId": "fsap-0abc"}
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode efs = objectMapper.valueToTree(response.getEntity())
                .path("taskDefinition").path("volumes").get(0).path("efsVolumeConfiguration");
        assertEquals("fsap-0abc", efs.path("authorizationConfig").path("accessPointId").asText());
        assertTrue(efs.path("rootDirectory").isMissingNode(), "rootDirectory should not be set");
    }

    @Test
    void registerTaskDefinitionAllowsNonDefaultRootDirectoryWithoutAccessPoint() throws Exception {
        String requestJson = """
                {
                  "family": "efs-valid-root-directory-family",
                  "containerDefinitions": [
                    {
                      "name": "app",
                      "image": "alpine:latest",
                      "mountPoints": [
                        {"sourceVolume": "customer-data", "containerPath": "/mnt/efs", "readOnly": false}
                      ]
                    }
                  ],
                  "volumes": [
                    {
                      "name": "customer-data",
                      "efsVolumeConfiguration": {
                        "fileSystemId": "fs-0123456789abcdef0",
                        "rootDirectory": "/dps"
                      }
                    }
                  ]
                }
                """;
        JsonNode request = objectMapper.readTree(requestJson);

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode efs = objectMapper.valueToTree(response.getEntity())
                .path("taskDefinition").path("volumes").get(0).path("efsVolumeConfiguration");
        assertEquals("/dps", efs.path("rootDirectory").asText());
        assertTrue(efs.path("authorizationConfig").path("accessPointId").isMissingNode(),
                "accessPointId should not be set");
    }

    // ── Host-volume safety ──────────────────────────────────────────────────

    @Test
    void registerTaskDefinitionRejectsRelativeHostSourcePath() throws Exception {
        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume("relative/path"));

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void registerTaskDefinitionRejectsTraversalHostSourcePath() throws Exception {
        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume("/host/abs/../../etc"));

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void registerTaskDefinitionRejectsBareRootHostSourcePath() throws Exception {
        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume("/"));

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void registerTaskDefinitionRejectsDockerSocketHostVolume() throws Exception {
        // Allow any host path so a rejection here can only come from the Docker-socket check
        // itself, not the fail-closed default (proving the socket-specific rule fires).
        when(config.services().ecs().allowUnsafeHostVolumes()).thenReturn(true);

        JsonNode varRun = objectMapper.readTree(registerRequestWithHostVolume("/var/run/docker.sock"));
        AwsException varRunEx = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", varRun, "us-east-1"));
        assertEquals("InvalidParameterException", varRunEx.getErrorCode());

        JsonNode run = objectMapper.readTree(registerRequestWithHostVolume("/run/docker.sock"));
        AwsException runEx = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", run, "us-east-1"));
        assertEquals("InvalidParameterException", runEx.getErrorCode());
    }

    @Test
    void registerTaskDefinitionRejectsDockerSocketAncestorDirectories() throws Exception {
        // Mounting a directory that CONTAINS the socket (/var/run, /run) exposes docker.sock
        // just as directly as naming the socket file outright, and must be blocked even when
        // allow-unsafe-host-volumes is true.
        when(config.services().ecs().allowUnsafeHostVolumes()).thenReturn(true);

        JsonNode varRun = objectMapper.readTree(registerRequestWithHostVolume("/var/run"));
        AwsException varRunEx = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", varRun, "us-east-1"));
        assertEquals("InvalidParameterException", varRunEx.getErrorCode());

        JsonNode run = objectMapper.readTree(registerRequestWithHostVolume("/run"));
        AwsException runEx = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", run, "us-east-1"));
        assertEquals("InvalidParameterException", runEx.getErrorCode());
    }

    @Test
    void registerTaskDefinitionRejectsHostSourcePathByDefault() throws Exception {
        // With host-volume-roots empty and allow-unsafe-host-volumes false (the shipped
        // defaults, unstubbed here), any host sourcePath is rejected fail-closed and the
        // message names both config keys so an operator knows how to opt in.
        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume("/some/data"));

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("floci.services.ecs.host-volume-roots"),
                "message must name the roots config key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("FLOCI_SERVICES_ECS_HOST_VOLUME_ROOTS"),
                "message must name the roots env var: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("floci.services.ecs.allow-unsafe-host-volumes"),
                "message must name the unsafe-flag config key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("FLOCI_SERVICES_ECS_ALLOW_UNSAFE_HOST_VOLUMES"),
                "message must name the unsafe-flag env var: " + ex.getMessage());
    }

    @Test
    void registerTaskDefinitionRejectsHostSourcePathOutsideConfiguredRoots() throws Exception {
        when(config.services().ecs().hostVolumeRoots()).thenReturn(Optional.of(List.of("/approved")));
        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume("/not-approved/data"));

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void registerTaskDefinitionAllowsHostSourcePathInsideConfiguredRoots() throws Exception {
        when(config.services().ecs().hostVolumeRoots()).thenReturn(Optional.of(List.of("/approved")));
        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume("/approved/sub/data"));

        Response response = handler.handle("RegisterTaskDefinition", request, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");
        assertEquals("/approved/sub/data",
                td.path("volumes").get(0).path("host").path("sourcePath").asText());
    }

    @Test
    void registerTaskDefinitionAllowUnsafeHostVolumesBypassesRootCheckButNotDockerSocket() throws Exception {
        when(config.services().ecs().hostVolumeRoots()).thenReturn(Optional.of(List.of("/approved")));
        when(config.services().ecs().allowUnsafeHostVolumes()).thenReturn(true);

        JsonNode outsideRoot = objectMapper.readTree(registerRequestWithHostVolume("/anywhere/else"));
        Response response = handler.handle("RegisterTaskDefinition", outsideRoot, "us-east-1");
        JsonNode td = objectMapper.valueToTree(response.getEntity()).path("taskDefinition");
        assertEquals("/anywhere/else",
                td.path("volumes").get(0).path("host").path("sourcePath").asText());

        JsonNode socket = objectMapper.readTree(registerRequestWithHostVolume("/var/run/docker.sock"));
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", socket, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void registerTaskDefinitionRejectsTheSocketNamedByDockerHostEnv(@TempDir Path tempDir) throws Exception {
        // With floci.docker.docker-host at its default, Floci's own Docker client connects to
        // whatever DOCKER_HOST names, so that socket is the live daemon socket and must be
        // protected exactly like /var/run/docker.sock, even with allow-unsafe-host-volumes.
        Path daemonSocket = tempDir.resolve("floci-docker.sock");
        when(config.docker().dockerHost()).thenReturn("unix:///var/run/docker.sock");
        when(config.docker().dockerConfigPath())
                .thenReturn(Optional.of(tempDir.resolve("no-docker-config").toString()));
        when(config.services().ecs().allowUnsafeHostVolumes()).thenReturn(true);
        handler = new EcsJsonHandler(service, objectMapper, new HostVolumePolicy(config,
                environment(Map.of("DOCKER_HOST", "unix://" + daemonSocket))));

        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume(daemonSocket.toString()));
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void registerTaskDefinitionRejectsTheDirectoryHoldingTheActiveContextSocket(@TempDir Path tempDir)
            throws Exception {
        // Colima, OrbStack and Rancher Desktop publish their daemon socket through a Docker
        // context rather than DOCKER_HOST. Floci's client follows the active context, so the
        // directory holding that socket exposes the daemon just like /var/run does.
        Path dockerConfigDir = tempDir.resolve("docker-config");
        Path socketDir = tempDir.resolve("colima");
        writeContextFixture(dockerConfigDir, "colima", "unix://" + socketDir.resolve("docker.sock"));
        when(config.docker().dockerHost()).thenReturn("unix:///var/run/docker.sock");
        when(config.docker().dockerConfigPath()).thenReturn(Optional.of(dockerConfigDir.toString()));
        when(config.services().ecs().allowUnsafeHostVolumes()).thenReturn(true);
        handler = new EcsJsonHandler(service, objectMapper, new HostVolumePolicy(config, environment(Map.of())));

        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume(socketDir.toString()));
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    private static Function<String, String> environment(Map<String, String> variables) {
        return variables::get;
    }

    /** Lays out {@code config.json} and a context's {@code meta.json} the way the Docker CLI does. */
    private static void writeContextFixture(Path dockerConfigDir, String contextName, String host) throws Exception {
        Files.createDirectories(dockerConfigDir);
        Files.writeString(dockerConfigDir.resolve("config.json"), "{\"currentContext\":\"" + contextName + "\"}");
        byte[] nameHash = MessageDigest.getInstance("SHA-256").digest(contextName.getBytes(StandardCharsets.UTF_8));
        Path metaDir = dockerConfigDir.resolve("contexts").resolve("meta").resolve(HexFormat.of().formatHex(nameHash));
        Files.createDirectories(metaDir);
        Files.writeString(metaDir.resolve("meta.json"),
                "{\"Name\":\"" + contextName + "\",\"Metadata\":{},"
                        + "\"Endpoints\":{\"docker\":{\"Host\":\"" + host + "\",\"SkipTLSVerify\":false}}}");
    }

    @Test
    void registerTaskDefinitionRejectsSymlinkEscapeOutsideConfiguredRoot(@TempDir Path tempDir) throws IOException {
        Path approvedRoot = tempDir.resolve("approved");
        Path outsideTarget = tempDir.resolve("outside");
        Files.createDirectories(approvedRoot);
        Files.createDirectories(outsideTarget);
        Path escapeLink = approvedRoot.resolve("escape");
        Files.createSymbolicLink(escapeLink, outsideTarget);

        when(config.services().ecs().hostVolumeRoots())
                .thenReturn(Optional.of(List.of(approvedRoot.toString())));
        JsonNode request = objectMapper.readTree(registerRequestWithHostVolume(escapeLink.toString()));

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("RegisterTaskDefinition", request, "us-east-1"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }
}
