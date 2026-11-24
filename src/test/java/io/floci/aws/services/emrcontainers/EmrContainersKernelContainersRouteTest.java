package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Calls to {@link EmrContainersKernelContainersRoute}, as an endpoint's gateway makes them for its kernels' containers:
 * a mock of the service stands behind it.
 */
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EmrContainersKernelContainersRouteTest {

    private static final String ENDPOINT = "ep0123456789a";
    private static final String KERNEL = "0b9e3c4e-6a1f-4c2d-9b8e-1f2a3b4c5d6e";
    private static final String PATH = EmrContainersKernelContainersRoute.PATH + ENDPOINT + "/" + KERNEL;

    private Vertx vertx;
    private HttpServer floci;
    private HttpClient client;
    private EmrContainersService service;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        service = mock(EmrContainersService.class);
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.isEnabled(eq(EmrContainersService.SERVICE))).thenReturn(true);
        Router router = Router.router(vertx);
        // As Quarkus hands a route the request: paused.
        router.route().handler(context -> {
            context.request().pause();
            context.next();
        });
        new EmrContainersKernelContainersRoute(service, serviceConfigAccess, vertx).register(router);
        floci = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void startGoesToTheServiceWithItsSecretAndBody() throws Exception {
        Answer started = request(HttpMethod.POST, "a-secret", "{\"kernel\": \"python3\"}");

        assertEquals(201, started.status);
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).startKernelContainer(eq(ENDPOINT), eq(KERNEL), eq("a-secret"), body.capture());
        assertEquals("python3", body.getValue().path("kernel").textValue());
    }

    @Test
    void refusalGivesItsStatusAndMessage() throws Exception {
        when(service.startKernelContainer(anyString(), anyString(), any(), any())).thenReturn(
                new EmrContainersService.KernelContainerRefusal(403, "not signed"));
        when(service.stopKernelContainer(anyString(), anyString(), any())).thenReturn(
                new EmrContainersService.KernelContainerRefusal(403, "not signed"));

        Answer start = request(HttpMethod.POST, null, "{}");
        Answer stop = request(HttpMethod.DELETE, null, null);

        assertEquals(403, start.status);
        assertEquals("not signed", new JsonObject(start.body).getString("message"));
        assertEquals(403, stop.status);
    }

    @Test
    void stopGoesToTheService() throws Exception {
        Answer stopped = request(HttpMethod.DELETE, "a-secret", null);

        assertEquals(204, stopped.status);
        verify(service).stopKernelContainer(ENDPOINT, KERNEL, "a-secret");
    }

    @Test
    void startWithoutAJsonObjectOrWithAnotherMethodIsRefused() throws Exception {
        assertEquals(400, request(HttpMethod.POST, "a-secret", "not json").status);
        assertEquals(400, request(HttpMethod.POST, "a-secret", "[]").status);
        assertEquals(405, request(HttpMethod.GET, "a-secret", null).status);
    }

    private Answer request(HttpMethod method, String secret, String body) throws Exception {
        RequestOptions options = new RequestOptions().setMethod(method).setHost("127.0.0.1")
                .setPort(floci.actualPort()).setURI(PATH);
        if (secret != null) {
            options.addHeader(EmrContainersGateways.SECRET_HEADER, secret);
        }
        return await(client.request(options)
                .compose(request -> body == null ? request.send() : request.send(Buffer.buffer(body)))
                .compose(response -> response.body()
                        .map(read -> new Answer(response.statusCode(), read.toString()))));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record Answer(int status, String body) {
    }
}
