package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsTaskRoleCredentialsServerTest {

    @Test
    void bindsOnlyToAwsTaskMetadataAddress() {
        Vertx vertx = mock(Vertx.class);
        HttpServer httpServer = mock(HttpServer.class, RETURNS_SELF);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        EcsTaskRoleCredentials credentials = mock(EcsTaskRoleCredentials.class);
        when(credentials.enabled()).thenReturn(true);
        when(config.services().ecs().taskRoleCredentialsPort()).thenReturn(18080);
        when(vertx.createHttpServer()).thenReturn(httpServer);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Handler<AsyncResult<HttpServer>> callback =
                    (Handler<AsyncResult<HttpServer>>) invocation.getArgument(2);
            callback.handle(Future.succeededFuture(httpServer));
            return httpServer;
        }).when(httpServer).listen(eq(18080), eq(EcsTaskRoleCredentialsServer.TASK_METADATA_HOST), any());

        EcsTaskRoleCredentialsServer server =
                new EcsTaskRoleCredentialsServer(vertx, config, credentials);

        server.start().join();

        verify(httpServer).listen(eq(18080), eq(EcsTaskRoleCredentialsServer.TASK_METADATA_HOST), any());
    }

    @Test
    void failsClosedWhenMetadataAddressCannotBind() {
        Vertx vertx = mock(Vertx.class);
        HttpServer httpServer = mock(HttpServer.class, RETURNS_SELF);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        EcsTaskRoleCredentials credentials = mock(EcsTaskRoleCredentials.class);
        IllegalStateException bindFailure = new IllegalStateException("address unavailable");
        when(credentials.enabled()).thenReturn(true);
        when(config.services().ecs().taskRoleCredentialsPort()).thenReturn(80);
        when(vertx.createHttpServer()).thenReturn(httpServer);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Handler<AsyncResult<HttpServer>> callback =
                    (Handler<AsyncResult<HttpServer>>) invocation.getArgument(2);
            callback.handle(Future.failedFuture(bindFailure));
            return httpServer;
        }).when(httpServer).listen(eq(80), eq(EcsTaskRoleCredentialsServer.TASK_METADATA_HOST), any());

        EcsTaskRoleCredentialsServer server =
                new EcsTaskRoleCredentialsServer(vertx, config, credentials);

        CompletionException failure = assertThrows(CompletionException.class, () -> server.start().join());

        assertSame(bindFailure, failure.getCause());
        verify(httpServer).listen(eq(80), eq(EcsTaskRoleCredentialsServer.TASK_METADATA_HOST), any());
    }

    @Test
    void responseUsesAwsContainerCredentialsShape() {
        EcsTaskRoleCredentials.IssuedCredentials issued = new EcsTaskRoleCredentials.IssuedCredentials(
                "arn:aws:ecs:us-east-1:111122223333:task/default/task-a",
                "arn:aws:iam::111122223333:role/task-role",
                "/v2/credentials/path-token",
                new SessionCreds("ASIAEXAMPLE", "secret", "session-token"),
                Instant.parse("2030-01-01T00:00:00Z"),
                Instant.parse("2029-12-31T23:00:00Z"));

        String json = EcsTaskRoleCredentialsServer.credentialsJson(issued);

        assertTrue(json.contains("\"RoleArn\":\"arn:aws:iam::111122223333:role/task-role\""));
        assertTrue(json.contains("\"AccessKeyId\":\"ASIAEXAMPLE\""));
        assertTrue(json.contains("\"SecretAccessKey\":\"secret\""));
        assertTrue(json.contains("\"Token\":\"session-token\""));
        assertTrue(json.contains("\"Expiration\":\"2030-01-01T00:00:00Z\""));
        assertEquals(1, json.chars().filter(c -> c == '{').count());
    }
}
