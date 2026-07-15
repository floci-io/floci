package io.github.hectorvent.floci.services.lambda.runtime;

import io.github.hectorvent.floci.services.lambda.model.ExtensionEvent;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.github.hectorvent.floci.services.lambda.model.RegisteredExtension;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-container HTTP server implementing the AWS Lambda Runtime API and, for
 * Lambda Extensions (e.g. aws-lambda-web-adapter), the Extensions API.
 * NOT a CDI bean — instances are created by RuntimeApiServerFactory.
 *
 * The container's language runtime connects to this server to:
 * - Poll for the next invocation (GET /runtime/invocation/next)
 * - Report success (POST /runtime/invocation/{requestId}/response)
 * - Report failure (POST /runtime/invocation/{requestId}/error)
 *
 * Extension processes (binaries under /opt/extensions/) connect to this server to:
 * - Register for lifecycle events (POST /extension/register)
 * - Poll for the next event (GET /extension/event/next)
 * - Report an init/exit error (POST /extension/init/error, /extension/exit/error)
 *
 * Extension event delivery is intentionally decoupled from invoke completion: unlike real
 * AWS (where the Invoke phase only ends once the runtime AND every registered extension have
 * each signaled done via /next), completing a PendingInvocation's resultFuture here depends
 * only on the runtime's own /response or /error call. Extensions are still notified of every
 * INVOKE/SHUTDOWN via their own /event/next polling loop. This is a deliberate simplification:
 * it's enough for extensions like aws-lambda-web-adapter (whose registration alone is what
 * gates it starting its proxy loop) without taking on exact post-invoke completion timing,
 * which mainly matters for extensions doing work after the response is already returned to
 * the client (e.g. flushing telemetry) — not a fidelity floci's local dev/test use case needs.
 */
public class RuntimeApiServer {

    private static final Logger LOG = Logger.getLogger(RuntimeApiServer.class);

    private static final String RUNTIME_API_VERSION = "2018-06-01";
    private static final String NEXT_PATH = "/" + RUNTIME_API_VERSION + "/runtime/invocation/next";
    private static final String RESPONSE_PATH = "/" + RUNTIME_API_VERSION + "/runtime/invocation/:requestId/response";
    private static final String ERROR_PATH = "/" + RUNTIME_API_VERSION + "/runtime/invocation/:requestId/error";
    private static final String INIT_ERROR_PATH = "/" + RUNTIME_API_VERSION + "/runtime/init/error";

    private static final String EXTENSIONS_API_VERSION = "2020-01-01";
    private static final String EXTENSION_REGISTER_PATH = "/" + EXTENSIONS_API_VERSION + "/extension/register";
    private static final String EXTENSION_NEXT_PATH = "/" + EXTENSIONS_API_VERSION + "/extension/event/next";
    private static final String EXTENSION_INIT_ERROR_PATH = "/" + EXTENSIONS_API_VERSION + "/extension/init/error";
    private static final String EXTENSION_EXIT_ERROR_PATH = "/" + EXTENSIONS_API_VERSION + "/extension/exit/error";

    private static final String EXTENSION_NAME_HEADER = "Lambda-Extension-Name";
    private static final String EXTENSION_ID_HEADER = "Lambda-Extension-Identifier";
    private static final String EXTENSION_EVENT_ID_HEADER = "Lambda-Extension-Event-Identifier";

    private static final byte[] CONTAINER_STOPPED_PAYLOAD =
            "{\"errorMessage\":\"Container stopped\",\"errorType\":\"ContainerStopped\"}".getBytes();

    // Acknowledgement body for the /response, /error and /init/error endpoints. Some
    // runtime clients (e.g. .NET's Amazon.Lambda.RuntimeSupport) deserialize it and
    // fail on an empty body.
    private static final String STATUS_OK_BODY = "{\"status\":\"OK\"}";

    private final Vertx vertx;
    private final int port;

    // Invocations queued before a /next poller arrived.
    private final ConcurrentLinkedQueue<PendingInvocation> pendingQueue = new ConcurrentLinkedQueue<>();

    // /next callers parked while the pending queue is empty.
    private final ConcurrentLinkedQueue<RoutingContext> waitingContexts = new ConcurrentLinkedQueue<>();

    private final ConcurrentHashMap<String, PendingInvocation> inFlight = new ConcurrentHashMap<>();

    // Extensions registered via /extension/register, keyed by their generated identifier.
    private final ConcurrentHashMap<String, RegisteredExtension> extensions = new ConcurrentHashMap<>();

    private volatile HttpServer httpServer;
    private volatile boolean stopped;
    private volatile CompletableFuture<Void> closeFuture;

    // Set by ContainerLauncher once it knows which function this server instance is for (the
    // factory creates the server generically, before that's known) — used only to populate the
    // Extensions API register response.
    private volatile String functionName = "function";
    private volatile String functionVersion = "$LATEST";
    private volatile String handler = "";

    RuntimeApiServer(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }

    public void setFunctionMetadata(String functionName, String functionVersion, String handler) {
        this.functionName = functionName != null ? functionName : "function";
        this.functionVersion = functionVersion != null ? functionVersion : "$LATEST";
        this.handler = handler != null ? handler : "";
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
            sendStatusOk(ctx);
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
            sendStatusOk(ctx);
        });

        // POST /runtime/init/error — runtime initialization failure
        router.post(INIT_ERROR_PATH).handler(ctx -> {
            LOG.warnv("Lambda runtime reported init error on port {0}", String.valueOf(port));
            sendStatusOk(ctx);
        });

        // POST /extension/register — an extension process (e.g. aws-lambda-web-adapter)
        // registers to receive lifecycle events. Real AWS requires the Lambda-Extension-Name
        // header to equal the extension's own file name; floci does not validate that here
        // (the identifier it hands back is sufficient for the extension to poll /event/next).
        router.post(EXTENSION_REGISTER_PATH).handler(ctx -> {
            String name = ctx.request().getHeader(EXTENSION_NAME_HEADER);
            if (name == null || name.isBlank()) {
                ctx.response().setStatusCode(400)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"errorMessage\":\"Missing Lambda-Extension-Name header\"}");
                return;
            }
            List<String> events = List.of("INVOKE", "SHUTDOWN");
            var body = ctx.body().asJsonObject();
            if (body != null && body.getJsonArray("events") != null) {
                events = body.getJsonArray("events").stream().map(String::valueOf).toList();
            }
            String identifier = UUID.randomUUID().toString();
            extensions.put(identifier, new RegisteredExtension(identifier, name, events));
            LOG.infov("Extension registered: {0} ({1}), events={2}", name, identifier, events);

            JsonObject responseBody = new JsonObject()
                    .put("functionName", functionName)
                    .put("functionVersion", functionVersion)
                    .put("handler", handler);
            ctx.response()
                    .setStatusCode(200)
                    .putHeader(EXTENSION_ID_HEADER, identifier)
                    .putHeader("Content-Type", "application/json")
                    .end(responseBody.encode());
        });

        // GET /extension/event/next — blocks until the next INVOKE or SHUTDOWN event for this
        // extension. Mirrors NEXT_PATH's park/dispatch pattern, scoped per-extension since each
        // extension has its own independent event queue.
        router.get(EXTENSION_NEXT_PATH).handler(ctx -> {
            String identifier = ctx.request().getHeader(EXTENSION_ID_HEADER);
            RegisteredExtension extension = identifier != null ? extensions.get(identifier) : null;
            if (extension == null) {
                ctx.response().setStatusCode(403)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"errorMessage\":\"Unknown or missing Lambda-Extension-Identifier\"}");
                return;
            }
            // Poll before checking stopped: stop() offers a SHUTDOWN event to pendingEvents for any
            // extension it doesn't find already parked, so a request arriving just after stopped is
            // set (but after that offer landed) must still see it — otherwise it gets a bare 204 and
            // the queued SHUTDOWN is orphaned, never delivered.
            ExtensionEvent event = extension.getPendingEvents().poll();
            if (event != null) {
                sendExtensionEvent(ctx, event);
                return;
            }
            if (stopped) {
                ctx.response().setStatusCode(204).end();
                return;
            }
            extension.setWaitingContext(ctx);
            // Re-check races the same way NEXT_PATH does: an event or stop() may have landed
            // between our poll() and setWaitingContext().
            if (stopped && extension.takeWaitingContext() != null) {
                ctx.response().setStatusCode(204).end();
                return;
            }
            ExtensionEvent raced = extension.getPendingEvents().poll();
            if (raced != null) {
                if (extension.takeWaitingContext() != null) {
                    sendExtensionEvent(ctx, raced);
                } else {
                    // A concurrent notifyExtensionsOfInvoke/stop() already consumed the waiting
                    // context (dispatching directly via runOnContext rather than the queue) between
                    // our poll() and this check — raced is a real event we already removed from the
                    // queue, so it must go back or it's silently lost for this extension.
                    extension.getPendingEvents().offer(raced);
                }
            }
        });

        // POST /extension/init/error, /extension/exit/error — an extension reports it can't
        // continue. Floci doesn't restart the execution environment on this (unlike real AWS);
        // it's enough to unregister the extension so future event fan-out skips it.
        router.post(EXTENSION_INIT_ERROR_PATH).handler(ctx -> handleExtensionFatalError(ctx, "init"));
        router.post(EXTENSION_EXIT_ERROR_PATH).handler(ctx -> handleExtensionFatalError(ctx, "exit"));

        long deadline = System.currentTimeMillis() + 5000;
        tryListen(started, router, deadline);

        return started;
    }

    private void tryListen(CompletableFuture<Void> started, Router router, long deadline) {
        if (started.isDone()) return;
        httpServer = vertx.createHttpServer(new HttpServerOptions()
                .setMaxFormAttributeSize(-1));
        httpServer.requestHandler(router).listen(port, "0.0.0.0", result -> {
            if (result.succeeded()) {
                LOG.infov("RuntimeApiServer started on port {0}", String.valueOf(port));
                started.complete(null);
            } else {
                if (System.currentTimeMillis() < deadline) {
                    LOG.debugv("RuntimeApiServer failed to bind on port {0}, retrying in 100ms...", String.valueOf(port));
                    httpServer.close(ar -> vertx.setTimer(100, id -> tryListen(started, router, deadline)));
                } else {
                    LOG.errorv(result.cause(), "RuntimeApiServer failed to bind on port {0}", String.valueOf(port));
                    started.completeExceptionally(result.cause());
                }
            }
        });
    }

    public synchronized CompletableFuture<Void> stop() {
        if (closeFuture != null) {
            return closeFuture;
        }
        stopped = true;
        CompletableFuture<Void> closed = new CompletableFuture<>();
        closeFuture = closed;
        if (httpServer != null) {
            httpServer.close(ar -> {
                if (ar.succeeded()) {
                    LOG.debugv("RuntimeApiServer on port {0} closed", String.valueOf(port));
                    closed.complete(null);
                } else {
                    LOG.warnv(ar.cause(), "RuntimeApiServer on port {0} failed to close cleanly", String.valueOf(port));
                    closed.completeExceptionally(ar.cause());
                }
            });
        } else {
            closed.complete(null);
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

        // Notify every extension subscribed to SHUTDOWN, same park/dispatch pattern as the
        // runtime poller above.
        ExtensionEvent shutdownEvent = ExtensionEvent.shutdown(System.currentTimeMillis() + 2000, "SPINDOWN");
        extensions.values().forEach(ext -> {
            if (!ext.isSubscribedTo(ExtensionEvent.Type.SHUTDOWN)) {
                return;
            }
            RoutingContext ctx = ext.takeWaitingContext();
            if (ctx != null) {
                vertx.runOnContext(v -> {
                    if (!ctx.response().ended()) {
                        sendExtensionEvent(ctx, shutdownEvent);
                    }
                });
            } else {
                ext.getPendingEvents().offer(shutdownEvent);
            }
        });

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

        return closed;
    }

    public CompletableFuture<InvokeResult> enqueue(PendingInvocation invocation) {
        if (stopped) {
            invocation.getResultFuture().complete(
                    new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, invocation.getRequestId()));
            return invocation.getResultFuture();
        }

        notifyExtensionsOfInvoke(invocation);

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

    private void sendStatusOk(RoutingContext ctx) {
        ctx.response()
                .setStatusCode(202)
                .putHeader("Content-Type", "application/json")
                .end(STATUS_OK_BODY);
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

    /** Fans an INVOKE event out to every extension subscribed to it, same park/dispatch pattern
     *  used for the runtime's own /next queue, scoped per-extension. */
    private void notifyExtensionsOfInvoke(PendingInvocation invocation) {
        if (extensions.isEmpty()) {
            return;
        }
        ExtensionEvent event = ExtensionEvent.invoke(
                invocation.getRequestId(), invocation.getDeadlineMs(), invocation.getFunctionArn());
        for (RegisteredExtension ext : extensions.values()) {
            if (!ext.isSubscribedTo(ExtensionEvent.Type.INVOKE)) {
                continue;
            }
            RoutingContext waitingCtx = ext.takeWaitingContext();
            if (waitingCtx != null) {
                vertx.runOnContext(v -> {
                    if (!waitingCtx.response().ended()) {
                        sendExtensionEvent(waitingCtx, event);
                    } else {
                        ext.getPendingEvents().offer(event);
                    }
                });
            } else {
                ext.getPendingEvents().offer(event);
            }
        }
    }

    private void sendExtensionEvent(RoutingContext ctx, ExtensionEvent event) {
        JsonObject body = new JsonObject().put("eventType", event.getType().name());
        if (event.getType() == ExtensionEvent.Type.INVOKE) {
            body.put("requestId", event.getRequestId())
                    .put("invokedFunctionArn", event.getFunctionArn())
                    .put("deadlineMs", event.getDeadlineMs());
        } else {
            body.put("shutdownReason", event.getShutdownReason())
                    .put("deadlineMs", event.getDeadlineMs());
        }
        ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .putHeader(EXTENSION_EVENT_ID_HEADER, UUID.randomUUID().toString())
                .end(body.encode());
    }

    private void handleExtensionFatalError(RoutingContext ctx, String phase) {
        String identifier = ctx.request().getHeader(EXTENSION_ID_HEADER);
        RegisteredExtension removed = identifier != null ? extensions.remove(identifier) : null;
        if (removed != null) {
            LOG.warnv("Extension {0} ({1}) reported {2} error on port {3}",
                    removed.getName(), identifier, phase, String.valueOf(port));
        } else {
            LOG.warnv("Unknown extension reported {0} error on port {1}", phase, String.valueOf(port));
        }
        ctx.response()
                .setStatusCode(202)
                .putHeader("Content-Type", "application/json")
                .end(STATUS_OK_BODY);
    }
}
