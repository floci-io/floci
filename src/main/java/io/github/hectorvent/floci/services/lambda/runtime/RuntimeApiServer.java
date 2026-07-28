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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * Locking discipline: every read/write of {@code stopped}, {@code pendingQueue},
 * {@code waitingContexts}, {@code extensions}, and each {@code RegisteredExtension}'s
 * {@code pendingEvents}/waiting context happens inside a single {@code synchronized(lock)}
 * block per operation. That block only *decides* what to do (dispatch to a specific
 * RoutingContext, complete a future, or nothing) and returns/collects that decision — the
 * actual {@code ctx.response()...end()}/{@code vertx.runOnContext(...)} calls always happen
 * after releasing the lock, so the lock is never held across a call into Vert.x and hold
 * times stay to sub-microsecond in-memory queue operations. One lock per server instance
 * (up to ~100 instances live at once, one per container) means no cross-container contention.
 * This intentionally stays non-blocking/event-loop-based throughout — NEXT_PATH and
 * EXTENSION_NEXT_PATH never park a real thread in {@code wait()} — because the container's
 * language-runtime bootstrap process holds one of these long-polls open for its entire idle
 * lifetime; blocking a bounded worker-thread pool (Quarkus's default is 20 threads) for that
 * long would exhaust it with only a handful of warm containers.
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

    // Guards stopped, pendingQueue, waitingContexts, extensions, closeFuture, and every
    // RegisteredExtension's pendingEvents/waitingContext. See class doc for the discipline.
    private final Object lock = new Object();

    // Invocations queued before a /next poller arrived. Guarded by lock.
    private final ArrayDeque<PendingInvocation> pendingQueue = new ArrayDeque<>();

    // /next callers parked while the pending queue is empty. Guarded by lock.
    private final ArrayDeque<RoutingContext> waitingContexts = new ArrayDeque<>();

    private final ConcurrentHashMap<String, PendingInvocation> inFlight = new ConcurrentHashMap<>();

    // Extensions registered via /extension/register, keyed by their generated identifier.
    // Guarded by lock.
    private final Map<String, RegisteredExtension> extensions = new HashMap<>();

    private volatile HttpServer httpServer;
    private boolean stopped;
    private CompletableFuture<Void> closeFuture;

    // Set once an extension reports an init/exit error. Real AWS treats both as fatal to the
    // execution environment, so the container must neither accept new work nor be reused.
    //
    // Read under `lock` alongside `stopped` in enqueue()/NEXT_PATH — a condemned environment
    // refuses new invocations rather than queueing them to a runtime that will never receive
    // them — and read without the lock by WarmPool (hence `volatile`) to decide whether to
    // retire the container instead of pooling it.
    //
    // Distinct from `stopped`: this does *not* tear the server down. The invocation already
    // in flight when the extension failed still completes normally through the runtime's own
    // /response call, matching AWS's treatment of the environment as condemned for future work
    // rather than aborted mid-invoke. WarmPool performs the actual teardown afterwards.
    private volatile boolean faulted;

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

    /**
     * True once a registered extension reported an init or exit error. The execution environment
     * is condemned: {@code WarmPool} must not return this container to the pool or hand it out
     * again, matching real AWS's treatment of both errors as fatal to the environment.
     */
    public boolean isFaulted() {
        return faulted;
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
            PendingInvocation toDispatch = null;
            boolean send204 = false;
            synchronized (lock) {
                // `faulted` alongside `stopped`: a condemned environment must not be handed work,
                // including an invocation that was queued just before the fault was reported.
                if (stopped || faulted) {
                    send204 = true;
                } else {
                    toDispatch = pendingQueue.poll();
                    if (toDispatch == null) {
                        waitingContexts.add(ctx);
                    }
                }
            }
            if (send204) {
                ctx.response().setStatusCode(204).end();
            } else if (toDispatch != null) {
                sendInvocation(ctx, toDispatch);
            }
            // else: parked — enqueue() or stop() will dispatch this ctx later.
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
            RegisteredExtension extension = new RegisteredExtension(identifier, name, events);
            synchronized (lock) {
                extensions.put(identifier, extension);
                if (stopped && extension.isSubscribedTo(ExtensionEvent.Type.SHUTDOWN)) {
                    // The server is already stopping — this extension will never see stop()'s
                    // own SHUTDOWN fan-out (that already ran), so queue one now to preserve
                    // "every registered extension eventually sees SHUTDOWN."
                    extension.getPendingEvents().offer(
                            ExtensionEvent.shutdown(System.currentTimeMillis() + 2000, "SPINDOWN"));
                }
            }
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
            ExtensionEvent toDispatch = null;
            boolean send204 = false;
            boolean unknownExtension = false;
            synchronized (lock) {
                RegisteredExtension extension = identifier != null ? extensions.get(identifier) : null;
                if (extension == null) {
                    unknownExtension = true;
                } else if (stopped) {
                    send204 = true;
                } else {
                    toDispatch = extension.getPendingEvents().poll();
                    if (toDispatch == null) {
                        extension.setWaitingContext(ctx);
                    }
                }
            }
            if (unknownExtension) {
                ctx.response().setStatusCode(403)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"errorMessage\":\"Unknown or missing Lambda-Extension-Identifier\"}");
            } else if (send204) {
                ctx.response().setStatusCode(204).end();
            } else if (toDispatch != null) {
                sendExtensionEvent(ctx, toDispatch);
            }
            // else: parked — notifyExtensionsOfInvoke()/stop() will dispatch this ctx later.
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

    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> closed;
        List<RoutingContext> contextsToClose204;
        List<Runnable> dispatches = new ArrayList<>();
        List<PendingInvocation> invocationsToFailContainerStopped;

        synchronized (lock) {
            if (closeFuture != null) {
                return closeFuture;
            }
            stopped = true;
            closed = new CompletableFuture<>();
            closeFuture = closed;

            contextsToClose204 = new ArrayList<>(waitingContexts);
            waitingContexts.clear();

            ExtensionEvent shutdownEvent = ExtensionEvent.shutdown(System.currentTimeMillis() + 2000, "SPINDOWN");
            for (RegisteredExtension ext : extensions.values()) {
                if (!ext.isSubscribedTo(ExtensionEvent.Type.SHUTDOWN)) {
                    continue;
                }
                RoutingContext waitingCtx = ext.takeWaitingContext();
                if (waitingCtx != null) {
                    dispatches.add(() -> {
                        if (!waitingCtx.response().ended()) {
                            sendExtensionEvent(waitingCtx, shutdownEvent);
                        }
                    });
                } else {
                    ext.getPendingEvents().offer(shutdownEvent);
                }
            }

            invocationsToFailContainerStopped = new ArrayList<>(pendingQueue);
            pendingQueue.clear();
        }

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
        for (RoutingContext ctx : contextsToClose204) {
            vertx.runOnContext(v -> {
                if (!ctx.response().ended()) {
                    ctx.response().setStatusCode(204).end();
                }
            });
        }

        // Dispatch SHUTDOWN to every extension that was already parked on /event/next.
        for (Runnable dispatch : dispatches) {
            vertx.runOnContext(v -> dispatch.run());
        }

        // Fail queued invocations that were never consumed by /next.
        for (PendingInvocation pending : invocationsToFailContainerStopped) {
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
        boolean rejected;
        List<Runnable> dispatches = new ArrayList<>();
        RoutingContext waitingCtxForInvocation = null;

        synchronized (lock) {
            // A condemned environment (an extension reported init/exit error) refuses new work the
            // same way a stopping one does. Without this the invocation is queued to a runtime that
            // will never be given it, and the caller hangs until the function timeout rather than
            // failing fast. Read inside the lock alongside `stopped` so the accept/reject decision
            // stays a single atomic step.
            rejected = stopped || faulted;
            if (!rejected) {
                if (!extensions.isEmpty()) {
                    ExtensionEvent event = ExtensionEvent.invoke(
                            invocation.getRequestId(), invocation.getDeadlineMs(), invocation.getFunctionArn());
                    for (RegisteredExtension ext : extensions.values()) {
                        if (!ext.isSubscribedTo(ExtensionEvent.Type.INVOKE)) {
                            continue;
                        }
                        RoutingContext waitingCtx = ext.takeWaitingContext();
                        if (waitingCtx != null) {
                            dispatches.add(() -> {
                                if (!waitingCtx.response().ended()) {
                                    sendExtensionEvent(waitingCtx, event);
                                } else {
                                    synchronized (lock) {
                                        ext.getPendingEvents().offer(event);
                                    }
                                }
                            });
                        } else {
                            ext.getPendingEvents().offer(event);
                        }
                    }
                }

                waitingCtxForInvocation = waitingContexts.poll();
                if (waitingCtxForInvocation == null) {
                    pendingQueue.offer(invocation);
                }
            }
        }

        if (rejected) {
            invocation.getResultFuture().complete(
                    new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, invocation.getRequestId()));
            return invocation.getResultFuture();
        }

        for (Runnable dispatch : dispatches) {
            vertx.runOnContext(v -> dispatch.run());
        }

        if (waitingCtxForInvocation != null) {
            final RoutingContext waitingCtx = waitingCtxForInvocation;
            vertx.runOnContext(v -> {
                if (!waitingCtx.response().ended()) {
                    sendInvocation(waitingCtx, invocation);
                } else {
                    // Connection closed between park and dispatch — re-queue.
                    synchronized (lock) {
                        pendingQueue.offer(invocation);
                    }
                }
            });
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
        RegisteredExtension removed;
        List<PendingInvocation> strandedInvocations;
        List<RoutingContext> strandedPollers;
        synchronized (lock) {
            removed = identifier != null ? extensions.remove(identifier) : null;
            // Condemn the environment regardless of whether the reporting extension was known: an
            // unrecognised identifier still means some extension in this container has failed
            // fatally, and serving further invocations from it would hide that failure. Set under
            // the lock alongside the unregistration so both land as one atomic state change.
            faulted = true;

            // Anything already queued or parked would otherwise wait forever, since the guards in
            // enqueue()/NEXT_PATH now refuse to move work through a condemned environment. Drain
            // both here so callers fail fast instead of hanging until the function timeout.
            strandedInvocations = new ArrayList<>(pendingQueue);
            pendingQueue.clear();
            strandedPollers = new ArrayList<>(waitingContexts);
            waitingContexts.clear();
        }
        if (removed != null) {
            LOG.warnv("Extension {0} ({1}) reported {2} error on port {3}; retiring execution environment",
                    removed.getName(), identifier, phase, String.valueOf(port));
        } else {
            LOG.warnv("Unknown extension reported {0} error on port {1}; retiring execution environment",
                    phase, String.valueOf(port));
        }

        for (PendingInvocation stranded : strandedInvocations) {
            stranded.getResultFuture().complete(
                    new InvokeResult(200, "Unhandled", CONTAINER_STOPPED_PAYLOAD, null, stranded.getRequestId()));
        }
        for (RoutingContext poller : strandedPollers) {
            vertx.runOnContext(v -> {
                if (!poller.response().ended()) {
                    poller.response().setStatusCode(204).end();
                }
            });
        }

        ctx.response()
                .setStatusCode(202)
                .putHeader("Content-Type", "application/json")
                .end(STATUS_OK_BODY);
    }
}
