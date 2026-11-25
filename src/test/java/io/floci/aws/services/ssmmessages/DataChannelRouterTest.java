package io.floci.aws.services.ssmmessages;

import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class DataChannelRouterTest {

    private final Vertx vertx = Vertx.vertx();
    private final HttpClient client = vertx.createHttpClient();
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        Router router = Router.router(vertx);
        new DataChannelRouter(List.of(
                new RejectingHandler("first-session", 401),
                new RejectingHandler("second-session", 403),
                new RejectingHandler("duplicate-session", 401),
                new RejectingHandler("duplicate-session", 403))).init(router);
        router.route().handler(context -> context.response().setStatusCode(204).end());
        port = await(vertx.createHttpServer().requestHandler(router).listen(0)).actualPort();
    }

    @AfterEach
    void closeVertx() throws Exception {
        await(vertx.close());
    }

    @ParameterizedTest
    @CsvSource({"first-session,401", "second-session,403"})
    void matchingHandlerCanRejectARequestBeforeTheUpgrade(String sessionId, int status) {
        assertEquals(status, rejectedUpgradeStatus(sessionId));
    }

    @Test
    void unknownSessionReturnsNotFound() {
        assertEquals(404, rejectedUpgradeStatus("unknown-session"));
    }

    @Test
    void duplicateOwnersCannotUpgradeTheSession() {
        assertEquals(500, rejectedUpgradeStatus("duplicate-session"));
    }

    @Test
    void ordinaryHttpRequestsFallThrough() throws Exception {
        HttpClientResponse response = await(client.request(HttpMethod.GET, port, "localhost", path("first-session"))
                .compose(HttpClientRequest::send));
        assertEquals(204, response.statusCode());
    }

    private int rejectedUpgradeStatus(String sessionId) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> await(client.webSocket(port, "localhost", path(sessionId))));
        return assertInstanceOf(UpgradeRejectedException.class, failure.getCause()).getStatus();
    }

    private static String path(String sessionId) {
        return DataChannelRouter.CHANNEL_PATH_PREFIX + sessionId;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record RejectingHandler(String sessionId, int status) implements DataChannelHandler {

        @Override
        public boolean supportsSession(String requestedSessionId) {
            return sessionId.equals(requestedSessionId);
        }

        @Override
        public void open(String requestedSessionId, RoutingContext context) {
            context.response().setStatusCode(status).end();
        }
    }
}
