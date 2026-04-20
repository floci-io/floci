package io.github.hectorvent.floci.services.lambda.runtime;

import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Per-container HTTP server implementing the AWS Lambda Runtime API.
 * NOT a CDI bean — instances are created by RuntimeApiServerFactory.
 *
 * The container's language runtime connects to this server to:
 * - Poll for the next invocation (GET /runtime/invocation/next)
 * - Report success (POST /runtime/invocation/{requestId}/response)
 * - Report failure (POST /runtime/invocation/{requestId}/error)
 */
public class RuntimeApiServer {

    private static final Logger LOG = Logger.getLogger(RuntimeApiServer.class);

    private static final String RUNTIME_API_VERSION = "2018-06-01";
    private static final String NEXT_PATH = "/" + RUNTIME_API_VERSION + "/runtime/invocation/next";
    private static final String RESPONSE_PATH = "/" + RUNTIME_API_VERSION + "/runtime/invocation/:requestId/response";
    private static final String ERROR_PATH = "/" + RUNTIME_API_VERSION + "/runtime/invocation/:requestId/error";
    private static final String INIT_ERROR_PATH = "/" + RUNTIME_API_VERSION + "/runtime/init/error";

    private static final byte[] CONTAINER_STOPPED_PAYLOAD =
            "{\"errorMessage\":\"Container stopped\",\"errorType\":\"ContainerStopped\"}".getBytes();

    // Sentinel used to wake a /next handler blocked in pendingQueue.poll() when stop() is called.
    // Compared by identity (==), not equals.
    private static final PendingInvocation SHUTDOWN_SENTINEL =
            new PendingInvocation("<shutdown>", new byte[0], 0L, "", new CompletableFuture<>());

    private final Vertx vertx;
    private final int port;
    private final LinkedBlockingQueue<PendingInvocation> pendingQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, PendingInvocation> inFlight = new ConcurrentHashMap<>();

    private volatile HttpServer httpServer;
    private volatile boolean stopped;

    RuntimeApiServer(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public CompletableFuture<Void> start() {
        CompletableFuture<Void> started = new CompletableFuture<>();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // GET /runtime/invocation/next — long-poll for next invocation
        router.get(NEXT_PATH).blockingHandler(ctx -> {
            try {
                PendingInvocation invocation = null;
                while (invocation == null && !stopped) {
                    invocation = pendingQueue.poll(30, TimeUnit.SECONDS);
                    if (invocation == SHUTDOWN_SENTINEL) {
                        invocation = null;
                        break;
                    }
                }
                // If stop() ran after poll() returned a real invocation (narrow race before
                // inFlight.put), complete it here so the caller doesn't hang.
                if (invocation != null && stopped) {
                    invocation.getResultFuture().complete(
                            new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, invocation.getRequestId()));
                    invocation = null;
                }
                if (invocation == null) {
                    ctx.response().setStatusCode(204).end();
                    return;
                }
                inFlight.put(invocation.getRequestId(), invocation);
                ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .putHeader("Lambda-Runtime-Aws-Request-Id", invocation.getRequestId())
                        .putHeader("Lambda-Runtime-Invoked-Function-Arn", invocation.getFunctionArn())
                        .putHeader("Lambda-Runtime-Deadline-Ms", String.valueOf(invocation.getDeadlineMs()))
                        .end(invocation.getPayload() != null
                                ? new String(invocation.getPayload())
                                : "{}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.response().setStatusCode(500).end();
            }
        });

        // POST /runtime/invocation/{requestId}/response — success
        router.post(RESPONSE_PATH).handler(ctx -> {
            String requestId = ctx.pathParam("requestId");
            PendingInvocation invocation = inFlight.remove(requestId);
            if (invocation != null) {
                byte[] payload = ctx.body().buffer() != null ? ctx.body().buffer().getBytes() : new byte[0];
                InvokeResult result = new InvokeResult(200, null, payload, null, requestId);
                invocation.getResultFuture().complete(result);
            }
            ctx.response().setStatusCode(202).end();
        });

        // POST /runtime/invocation/{requestId}/error — failure
        router.post(ERROR_PATH).handler(ctx -> {
            String requestId = ctx.pathParam("requestId");
            PendingInvocation invocation = inFlight.remove(requestId);
            if (invocation != null) {
                byte[] payload = ctx.body().buffer() != null ? ctx.body().buffer().getBytes() : new byte[0];
                String errorType = ctx.request().getHeader("Lambda-Runtime-Function-Error-Type");
                String functionError = errorType != null && errorType.contains("Runtime") ? "Unhandled" : "Handled";
                InvokeResult result = new InvokeResult(200, functionError, payload, null, requestId);
                invocation.getResultFuture().complete(result);
            }
            ctx.response().setStatusCode(202).end();
        });

        // POST /runtime/init/error — runtime initialization failure
        router.post(INIT_ERROR_PATH).handler(ctx -> {
            LOG.warnv("Lambda runtime reported init error on port {0}", port);
            ctx.response().setStatusCode(202).end();
        });

        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(router).listen(port, "0.0.0.0", result -> {
            if (result.succeeded()) {
                LOG.debugv("RuntimeApiServer started on port {0}", port);
                started.complete(null);
            } else {
                started.completeExceptionally(result.cause());
            }
        });

        return started;
    }

    public void stop() {
        stopped = true;
        if (httpServer != null) {
            httpServer.close();
        }

        // Drain queued invocations that were never consumed by /next, completing each future
        // with ContainerStopped so callers (LambdaExecutorService) don't hang waiting for a result.
        PendingInvocation pending;
        while ((pending = pendingQueue.poll()) != null) {
            if (pending == SHUTDOWN_SENTINEL) {
                continue;
            }
            pending.getResultFuture().complete(
                    new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, pending.getRequestId()));
        }

        // Offer sentinel AFTER drain so it cannot be discarded. Wakes any /next handler
        // currently blocked in poll(). N=1 today: a single RuntimeApiServer serves one container,
        // so one sentinel suffices.
        pendingQueue.offer(SHUTDOWN_SENTINEL);

        // Complete any in-flight invocations with error.
        inFlight.values().forEach(inv ->
                inv.getResultFuture().complete(
                        new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, inv.getRequestId())));
        inFlight.clear();
    }

    public CompletableFuture<InvokeResult> enqueue(PendingInvocation invocation) {
        if (stopped) {
            // stop() already ran; don't queue. Complete immediately so caller doesn't hang.
            invocation.getResultFuture().complete(
                    new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, invocation.getRequestId()));
            return invocation.getResultFuture();
        }
        pendingQueue.offer(invocation);
        // Close the check-then-offer race: if stop() ran between the guard and offer(),
        // the drain is done and our invocation would sit forever. Remove and complete.
        if (stopped && pendingQueue.remove(invocation)) {
            invocation.getResultFuture().complete(
                    new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, invocation.getRequestId()));
        }
        return invocation.getResultFuture();
    }
}
