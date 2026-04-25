package io.github.hectorvent.floci.services.lambda.runtime;

import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    private final Vertx vertx;
    private final int port;

    // Invocations queued before a /next poller arrived.
    private final ConcurrentLinkedQueue<PendingInvocation> pendingQueue = new ConcurrentLinkedQueue<>();

    // /next callers parked while the pending queue is empty.
    private final ConcurrentLinkedQueue<RoutingContext> waitingContexts = new ConcurrentLinkedQueue<>();

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

        // GET /runtime/invocation/next — AWS Runtime API contract: blocks until an invocation
        // arrives, then returns 200 with the invocation payload and required headers.
        // Uses a reactive pattern (no thread held while waiting) to avoid Vert.x worker pool
        // exhaustion when many warm containers poll concurrently.
        router.get(NEXT_PATH).handler(ctx -> {
            if (stopped) {
                ctx.response().setStatusCode(204).end();
                return;
            }
            PendingInvocation invocation = pendingQueue.poll();
            if (invocation != null) {
                if (stopped) {
                    invocation.getResultFuture().complete(
                            new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, invocation.getRequestId()));
                    ctx.response().setStatusCode(204).end();
                    return;
                }
                sendInvocation(ctx, invocation);
                return;
            }
            // No invocation queued yet — park this context until enqueue() wakes it.
            waitingContexts.add(ctx);
            // Re-check stop race: stop() may have drained waitingContexts before our add().
            if (stopped && waitingContexts.remove(ctx)) {
                ctx.response().setStatusCode(204).end();
                return;
            }
            // Re-check enqueue race: an invocation may have arrived between our poll() and add().
            PendingInvocation raced = pendingQueue.poll();
            if (raced != null && waitingContexts.remove(ctx)) {
                sendInvocation(ctx, raced);
            }
            // else: still parked — enqueue() will dispatch via vertx.runOnContext().
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

        // Wake any parked /next pollers with 204 (container shutting down — runtime will exit).
        RoutingContext waiting;
        while ((waiting = waitingContexts.poll()) != null) {
            final RoutingContext ctx = waiting;
            vertx.runOnContext(v -> {
                if (!ctx.response().ended()) {
                    ctx.response().setStatusCode(204).end();
                }
            });
        }

        // Drain queued invocations that were never consumed by /next.
        PendingInvocation pending;
        while ((pending = pendingQueue.poll()) != null) {
            pending.getResultFuture().complete(
                    new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, pending.getRequestId()));
        }

        // Complete any in-flight invocations with error.
        inFlight.values().forEach(inv ->
                inv.getResultFuture().complete(
                        new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, inv.getRequestId())));
        inFlight.clear();
    }

    public CompletableFuture<InvokeResult> enqueue(PendingInvocation invocation) {
        if (stopped) {
            invocation.getResultFuture().complete(
                    new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, invocation.getRequestId()));
            return invocation.getResultFuture();
        }
        // If a /next poller is already parked, dispatch immediately on the event loop.
        RoutingContext ctx = waitingContexts.poll();
        if (ctx != null) {
            final RoutingContext waitingCtx = ctx;
            vertx.runOnContext(v -> {
                if (!waitingCtx.response().ended()) {
                    sendInvocation(waitingCtx, invocation);
                } else {
                    // Connection closed between park and dispatch — re-queue.
                    pendingQueue.offer(invocation);
                }
            });
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

    private void sendInvocation(RoutingContext ctx, PendingInvocation invocation) {
        inFlight.put(invocation.getRequestId(), invocation);
      
        byte[] payload = invocation.getPayload();
        String body = (payload != null && payload.length > 0)
              ? new String(payload)
              : "{}";
        ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .putHeader("Lambda-Runtime-Aws-Request-Id", invocation.getRequestId())
                .putHeader("Lambda-Runtime-Invoked-Function-Arn", invocation.getFunctionArn())
                .putHeader("Lambda-Runtime-Deadline-Ms", String.valueOf(invocation.getDeadlineMs()))
                .end(body);
    }
}
