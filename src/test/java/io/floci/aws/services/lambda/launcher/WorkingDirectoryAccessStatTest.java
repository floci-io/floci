package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.transport.DockerHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wire contract of the archive stat probe behind the working directory check: one HEAD per
 * path, the mode read from the base64 JSON of the stat header, and a 404 or a file answered with
 * an unknown entry rather than a failure.
 */
class WorkingDirectoryAccessStatTest {

    private static final String STAT_HEADER = "X-Docker-Container-Path-Stat";

    @Test
    void theCheckProbesEachPathWithAHeadArchiveRequest() {
        DockerHttpClient http = statHttp(Map.of(
                "/var", missing(),
                "/var/task", missing()));

        require("/var/task", http);

        ArgumentCaptor<DockerHttpClient.Request> requests =
                ArgumentCaptor.forClass(DockerHttpClient.Request.class);
        verify(http, times(2)).execute(requests.capture());
        assertEquals(List.of("HEAD", "HEAD"), requests.getAllValues().stream()
                .map(DockerHttpClient.Request::method).toList());
        assertEquals(List.of("/containers/c-1/archive?path=%2Fvar", "/containers/c-1/archive?path=%2Fvar%2Ftask"),
                requests.getAllValues().stream().map(DockerHttpClient.Request::path).toList());
        requests.getAllValues().forEach(request -> {
            assertEquals(Map.of(), request.headers());
            assertEquals(null, request.bodyBytes());
            assertEquals(null, request.hijackedInput());
        });
    }

    @Test
    void aDirectoryStatStopsLambdasUserWhenOthersCannotEnter() {
        DockerHttpClient http = statHttp(Map.of(
                "/var", stat(1 << 31 | 0755),
                "/var/task", stat(1 << 31 | 0700)));

        LambdaInitException error = assertThrows(LambdaInitException.class,
                () -> require("/var/task", http));
        assertEquals("Runtime.InvalidEntrypoint", error.errorType());
    }

    @Test
    void aDirectoryStatEveryoneCanEnterPassesTheCheck() {
        DockerHttpClient http = statHttp(Map.of(
                "/var", stat(1 << 31 | 0755),
                "/var/task", stat(1 << 31 | 0711)));

        assertDoesNotThrow(() -> require("/var/task", http));
    }

    @Test
    void aFileStatIsNotHeldAgainstTheFunction() {
        DockerHttpClient http = statHttp(Map.of(
                "/var", stat(0755),
                "/var/task", stat(0755)));

        assertDoesNotThrow(() -> require("/var/task", http));
    }

    @Test
    void aMissingPathStatIsNotHeldAgainstTheFunction() {
        DockerHttpClient http = statHttp(Map.of(
                "/var", missing(),
                "/var/task", missing()));

        assertDoesNotThrow(() -> require("/var/task", http));
    }

    @Test
    void aSymlinkStatIsFollowedToItsTarget() {
        DockerHttpClient http = statHttp(Map.of(
                "/app", symlink("/home/nonroot"),
                "/home", stat(1 << 31 | 0755),
                "/home/nonroot", stat(1 << 31 | 0700)));

        assertThrows(LambdaInitException.class, () -> require("/app", http));
    }

    private static void require(String workingDir, DockerHttpClient http) {
        InspectContainerResponse inspected = mock(InspectContainerResponse.class);
        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getWorkingDir()).thenReturn(workingDir);
        when(inspected.getConfig()).thenReturn(config);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenReturn(inspected);
        DockerClient docker = mock(DockerClient.class);
        when(docker.inspectContainerCmd("c-1")).thenReturn(inspect);

        WorkingDirectoryAccess.requireLambdaUserCanEnter(docker, http, "c-1", List.of());
    }

    /** A control plane transport whose archive probes answer from {@code answers}, by path. */
    private static DockerHttpClient statHttp(Map<String, DockerHttpClient.Response> answers) {
        DockerHttpClient http = mock(DockerHttpClient.class);
        when(http.execute(any())).thenAnswer(invocation -> {
            DockerHttpClient.Request request = invocation.getArgument(0);
            String path = request.path();
            String probe = path.substring(path.indexOf("path=") + "path=".length());
            return answers.get(URLDecoder.decode(probe, StandardCharsets.UTF_8));
        });
        return http;
    }

    private static DockerHttpClient.Response stat(int goMode) {
        return statResponse(200, "{\"mode\":" + goMode + ",\"linkTarget\":\"\"}");
    }

    private static DockerHttpClient.Response symlink(String target) {
        return statResponse(200, "{\"mode\":" + (1 << 27) + ",\"linkTarget\":\"" + target + "\"}");
    }

    private static DockerHttpClient.Response missing() {
        return statResponse(404, "{}");
    }

    private static DockerHttpClient.Response statResponse(int status, String json) {
        String stat = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return new DockerHttpClient.Response() {
            @Override
            public int getStatusCode() {
                return status;
            }

            @Override
            public Map<String, List<String>> getHeaders() {
                return status == 200 ? Map.of(STAT_HEADER, List.of(stat)) : Map.of();
            }

            @Override
            public InputStream getBody() {
                return InputStream.nullInputStream();
            }

            @Override
            public void close() {
            }
        };
    }
}
