package io.github.hectorvent.floci.services.lambda.runtime;

import io.github.hectorvent.floci.services.lambda.model.ExtensionEvent;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.github.hectorvent.floci.services.lambda.model.RegisteredExtension;
import io.vertx.core.Future;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
    private static final String EXTENSION_ACCEPT_FEATURE_HEADER = "Lambda-Extension-Accept-Feature";

    /** Required by AWS on both /extension/init/error and /extension/exit/error. */
    private static final String EXTENSION_ERROR_TYPE_HEADER = "Lambda-Extension-Function-Error-Type";

    /** Opt-in feature that adds {@code accountId} to the register response. */
    private static final String ACCOUNT_ID_FEATURE = "accountId";

    /** Matches the account used elsewhere when a function carries none (see LambdaService). */
    private static final String DEFAULT_ACCOUNT_ID = "000000000000";

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

    // Init-readiness barrier. ContainerLauncher launches extension binaries as detached `docker
    // exec`s and returns immediately, so without this the first invocation can reach the runtime
    // before an extension is ready — the adapter never sees that invoke and silently misses it.
    // ContainerLauncher declares how many binaries it launched via expectExtensions();
    // awaitExtensionsReady() blocks the launch until they are all ready (or the deadline passes).
    //
    // Readiness is the extension's *first /extension/event/next*, not its register call, matching
    // AWS's lifecycle: registering only obtains an identifier, whereas an extension that is
    // polling for events has finished its own initialisation and can actually receive an INVOKE.
    // An extension that registers and then spends time initialising is correctly still counted as
    // not-ready during that gap.
    //
    // A CountDownLatch is level-triggered, so an await() that arrives after the last extension
    // became ready returns immediately rather than missing the signal — the latch is created
    // before any extension can register, which is what makes that safe.
    private volatile CountDownLatch extensionsReady;

    // Set by ContainerLauncher once it knows which function this server instance is for (the
    // factory creates the server generically, before that's known) — used only to populate the
    // Extensions API register response.
    private volatile String functionName = "function";
    private volatile String functionVersion = "$LATEST";
    private volatile String handler = "";
    private volatile String accountId = DEFAULT_ACCOUNT_ID;

    RuntimeApiServer(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }

    public void setFunctionMetadata(String functionName, String functionVersion, String handler,
                                    String accountId) {
        this.functionName = functionName != null ? functionName : "function";
        this.functionVersion = functionVersion != null ? functionVersion : "$LATEST";
        this.handler = handler != null ? handler : "";
        this.accountId = accountId != null && !accountId.isBlank() ? accountId : DEFAULT_ACCOUNT_ID;
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

    /**
     * Declares how many extension binaries were launched for this container, arming the
     * init-readiness barrier that {@link #awaitExtensionsReady(long)} waits on.
     *
     * <p>Must be called <em>before</em> the extension processes are started, so the latch exists
     * before any of them can call {@code /extension/register}. A count of zero (the common case:
     * no {@code /opt/extensions} directory) leaves the barrier permanently open.
     */
    public void expectExtensions(int count) {
        extensionsReady = new CountDownLatch(Math.max(0, count));
    }

    /**
     * Blocks until every extension declared via {@link #expectExtensions(int)} is init-ready —
     * that is, until each has issued its first {@code /extension/event/next} — or the timeout
     * elapses.
     *
     * @return true if all expected extensions became ready; false if the wait timed out with some
     *         still missing, in which case the container is still usable — it simply starts
     *         serving invocations without the extensions that never checked in.
     */
    public boolean awaitExtensionsReady(long timeoutMs) throws InterruptedException {
        CountDownLatch latch = extensionsReady;
        if (latch == null) {
            return true;
        }
        return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * True when this extension is currently parked on {@code /extension/event/next} — its request
     * reached the handler and was stored as the waiting context rather than answered immediately.
     *
     * <p>Package-private and used only by tests that race {@link #stop()} against a poll already in
     * flight. Such a test cannot use a sleep to decide the poll has landed: the request parking is
     * what makes {@code stop()}'s SHUTDOWN fan-out see a waiting context instead of queueing the
     * event for a connection that is about to be torn down, and on a loaded machine an arbitrary
     * sleep expires before that happens. Polling this instead makes the wait condition-based.
     *
     * <p>Read under {@code lock}, per the locking discipline in the class doc.
     */
    boolean isExtensionParked(String identifier) {
        synchronized (lock) {
            RegisteredExtension extension = extensions.get(identifier);
            return extension != null && extension.hasWaitingContext();
        }
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
            boolean environmentFaulted;
            synchronized (lock) {
                // AWS makes an init/exit error terminal for the whole Extensions API, not just for
                // runtime work: once the environment is condemned no further extension call
                // succeeds. Registering into a container about to be retired would hand back an
                // identifier that can never receive an event.
                //
                // Checked *inside* the lock, like every other guard on this flag:
                // handleExtensionFatalError sets `faulted` under the lock, so a volatile read
                // before acquiring it can be overtaken between the check and the put() below,
                // registering into an environment condemned a moment later.
                environmentFaulted = faulted;
                if (!environmentFaulted) {
                    extensions.put(identifier, extension);
                    if (stopped && extension.isSubscribedTo(ExtensionEvent.Type.SHUTDOWN)) {
                        // The server is already stopping — this extension will never see stop()'s
                        // own SHUTDOWN fan-out (that already ran), so queue one now to preserve
                        // "every registered extension eventually sees SHUTDOWN."
                        extension.getPendingEvents().offer(
                                ExtensionEvent.shutdown(System.currentTimeMillis() + 2000, "SPINDOWN"));
                    }
                }
            }
            if (environmentFaulted) {
                sendExtensionFaulted(ctx);
                return;
            }
            LOG.infov("Extension registered: {0} ({1}), events={2}", name, identifier, events);

            JsonObject responseBody = new JsonObject()
                    .put("functionName", functionName)
                    .put("functionVersion", functionVersion)
                    .put("handler", handler);
            // AWS only includes accountId when the extension opts in via Lambda-Extension-Accept-
            // Feature; adding it unconditionally would diverge from the real response shape for
            // extensions that never asked. The header is a comma-separated feature list.
            if (acceptsFeature(ctx.request().getHeader(EXTENSION_ACCEPT_FEATURE_HEADER), ACCOUNT_ID_FEATURE)) {
                responseBody.put("accountId", accountId);
            }
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
            boolean environmentFaulted = false;
            CountDownLatch readyLatch = null;
            synchronized (lock) {
                RegisteredExtension extension = identifier != null ? extensions.get(identifier) : null;
                if (extension == null) {
                    unknownExtension = true;
                } else if (faulted) {
                    // Read under the lock alongside `stopped`: handleExtensionFatalError() sets
                    // `faulted` and drains the parked pollers as one atomic change, so checking it
                    // here is what stops a poller parking again straight after that drain.
                    environmentFaulted = true;
                } else {
                    // AWS treats an extension as init-ready at its first /event/next, not at
                    // register: the extension has finished its own initialisation only once it
                    // starts polling. Count the readiness barrier down here, once per extension.
                    if (extension.markFirstNextReceived()) {
                        readyLatch = extensionsReady;
                    }
                    // Poll before checking `stopped`: stop() offers a SHUTDOWN to pendingEvents for
                    // any extension it doesn't find already parked, so a request arriving just
                    // after `stopped` was set — but after that offer landed — must still see it.
                    // Checking `stopped` first would answer 204 and orphan the queued SHUTDOWN.
                    toDispatch = extension.getPendingEvents().poll();
                    if (toDispatch == null) {
                        // Deliberate deviation: AWS documents only 200/403/500 here and never ends
                        // the long-poll with an empty response. Floci answers 204 once the server
                        // is stopping and there is genuinely nothing left to deliver, because the
                        // alternative is holding the connection open while the listener is torn
                        // down — the extension would see a dropped socket instead of a clean
                        // close. Only reachable for an extension not subscribed to SHUTDOWN (a
                        // subscribed one gets the real SHUTDOWN event from stop()'s fan-out, which
                        // the poll above returns), so no extension that asked to be told about
                        // shutdown learns about it this way.
                        if (stopped) {
                            send204 = true;
                        } else {
                            extension.setWaitingContext(ctx);
                        }
                    }
                }
            }
            if (readyLatch != null) {
                readyLatch.countDown();
            }
            if (unknownExtension) {
                ctx.response().setStatusCode(403)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"errorMessage\":\"Unknown or missing Lambda-Extension-Identifier\"}");
            } else if (environmentFaulted) {
                sendExtensionFaulted(ctx);
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
                .setMaxFormAttributeSize(-1)
                // Real AWS's Runtime/Extensions API is plain HTTP/1.1. Vert.x defaults to
                // accepting an h2c upgrade, which the JDK HttpClient used in tests and by some
                // language runtimes opts into unless told otherwise. That multiplexes every
                // request from one client (e.g. an extension's register + its own /event/next
                // long-poll) onto a single connection/stream-set, so stop()'s close of that
                // connection can race a just-flushed response's HTTP/2 stream-completion
                // bookkeeping instead of tearing down an already-idle connection — see #2180.
                // Disabling h2c makes the client fall back to a plain HTTP/1.1 request
                // automatically (no client-side change needed) and matches AWS's actual wire
                // protocol.
                .setHttp2ClearTextEnabled(false));
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
        List<Supplier<Future<Void>>> dispatches = new ArrayList<>();
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
                        if (waitingCtx.response().ended()) {
                            return Future.succeededFuture();
                        }
                        return sendExtensionEvent(waitingCtx, shutdownEvent);
                    });
                } else {
                    ext.getPendingEvents().offer(shutdownEvent);
                }
            }

            invocationsToFailContainerStopped = new ArrayList<>(pendingQueue);
            pendingQueue.clear();
        }

        Runnable closeServer = () -> {
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
        };

        // Wake any parked /next pollers with 204 (container shutting down — runtime will exit) and
        // dispatch SHUTDOWN to every extension already parked on /event/next, then close the HTTP
        // server. Each write is scheduled on the event loop via vertx.runOnContext(...), and the
        // server is closed only once every write's response has actually been flushed — tracked by
        // remainingWrites, decremented from each end() future's completion rather than when the
        // scheduled handler returns (response.end() is asynchronous, so the bytes are not on the
        // wire yet when the handler returns). So a parked extension's SHUTDOWN — or a poller's 204
        // — is fully written before its connection is torn down, instead of racing
        // httpServer.close() and orphaning the extension with an EOF. See issue #2142.
        int parkedWrites = contextsToClose204.size() + dispatches.size();
        if (parkedWrites == 0) {
            closeServer.run();
        } else {
            AtomicInteger remainingWrites = new AtomicInteger(parkedWrites);
            Runnable afterWrite = () -> {
                if (remainingWrites.decrementAndGet() == 0) {
                    closeServer.run();
                }
            };
            for (RoutingContext ctx : contextsToClose204) {
                vertx.runOnContext(v -> {
                    Future<Void> written = ctx.response().ended()
                            ? Future.succeededFuture()
                            : ctx.response().setStatusCode(204).end();
                    written.onComplete(ar -> afterWrite.run());
                });
            }
            for (Supplier<Future<Void>> dispatch : dispatches) {
                vertx.runOnContext(v -> dispatch.get().onComplete(ar -> afterWrite.run()));
            }
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

    /**
     * True if {@code headerValue} — the comma-separated {@code Lambda-Extension-Accept-Feature}
     * list an extension sends at registration — opts into {@code feature}. Matching is
     * case-insensitive and tolerant of surrounding whitespace; a null/blank header opts into
     * nothing.
     */
    private static boolean acceptsFeature(String headerValue, String feature) {
        if (headerValue == null || headerValue.isBlank()) {
            return false;
        }
        for (String candidate : headerValue.split(",")) {
            if (candidate.trim().equalsIgnoreCase(feature)) {
                return true;
            }
        }
        return false;
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

    private Future<Void> sendExtensionEvent(RoutingContext ctx, ExtensionEvent event) {
        JsonObject body = new JsonObject().put("eventType", event.getType().name());
        if (event.getType() == ExtensionEvent.Type.INVOKE) {
            body.put("requestId", event.getRequestId())
                    .put("invokedFunctionArn", event.getFunctionArn())
                    .put("deadlineMs", event.getDeadlineMs());
        } else {
            body.put("shutdownReason", event.getShutdownReason())
                    .put("deadlineMs", event.getDeadlineMs());
        }
        return ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .putHeader(EXTENSION_EVENT_ID_HEADER, UUID.randomUUID().toString())
                .end(body.encode());
    }

    /**
     * Response for any Extensions API call made after an extension reported an init/exit error.
     * AWS makes the fault terminal for the whole environment — no further extension call succeeds
     * — so this is a hard error rather than the 204 used for an orderly shutdown, which an
     * extension is entitled to read as "nothing right now, poll again".
     */
    private void sendExtensionFaulted(RoutingContext ctx) {
        ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json")
                .end("{\"errorType\":\"Extension.SandboxFaulted\","
                        + "\"errorMessage\":\"Execution environment condemned by an extension "
                        + "init/exit error\"}");
    }

    private void handleExtensionFatalError(RoutingContext ctx, String phase) {
        String identifier = ctx.request().getHeader(EXTENSION_ID_HEADER);
        String errorType = ctx.request().getHeader(EXTENSION_ERROR_TYPE_HEADER);

        // Validate before mutating anything. Retiring the execution environment is destructive and
        // unrecoverable, so a malformed report must not trigger it — otherwise a caller that got
        // the contract wrong silently kills a healthy container and the 202 tells them it worked.
        // AWS requires Lambda-Extension-Function-Error-Type on both error endpoints, and rejects
        // an unknown identifier rather than treating it as a fatal report.
        if (identifier == null || identifier.isBlank()) {
            ctx.response().setStatusCode(403)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"errorMessage\":\"Unknown or missing Lambda-Extension-Identifier\"}");
            return;
        }
        if (errorType == null || errorType.isBlank()) {
            ctx.response().setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"errorMessage\":\"Missing Lambda-Extension-Function-Error-Type header\"}");
            return;
        }

        RegisteredExtension removed;
        List<PendingInvocation> strandedInvocations;
        List<RoutingContext> strandedPollers;
        List<RoutingContext> strandedExtensionPollers;
        synchronized (lock) {
            removed = extensions.remove(identifier);
            if (removed == null) {
                // Unknown identifier: reject without condemning. Answered outside the lock below.
                strandedInvocations = List.of();
                strandedPollers = List.of();
                strandedExtensionPollers = List.of();
            } else {
                // Set under the lock alongside the unregistration so both land as one atomic
                // change.
                faulted = true;

                // Anything already queued or parked would otherwise wait forever, since the guards
                // in enqueue()/NEXT_PATH now refuse to move work through a condemned environment.
                // Drain both here so callers fail fast instead of hanging until the function
                // timeout.
                strandedInvocations = new ArrayList<>(pendingQueue);
                pendingQueue.clear();
                strandedPollers = new ArrayList<>(waitingContexts);
                waitingContexts.clear();

                // Sibling extensions parked on /event/next need the same treatment, and for the
                // same reason: the faulted guard in that handler means nothing will ever wake
                // them, so without this their long-poll stays open until stop() eventually runs
                // — up to a full function timeout away. Mirrors the extensions.values() sweep in
                // stop(); harmless with a single extension, but a second one hangs without it.
                strandedExtensionPollers = new ArrayList<>();
                for (RegisteredExtension ext : extensions.values()) {
                    RoutingContext waitingCtx = ext.takeWaitingContext();
                    if (waitingCtx != null) {
                        strandedExtensionPollers.add(waitingCtx);
                    }
                }
            }
        }

        if (removed == null) {
            LOG.warnv("Unknown extension identifier {0} reported {1} error on port {2}; ignoring",
                    identifier, phase, String.valueOf(port));
            ctx.response().setStatusCode(403)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"errorMessage\":\"Unknown or missing Lambda-Extension-Identifier\"}");
            return;
        }

        LOG.warnv("Extension {0} ({1}) reported {2} error ({3}) on port {4}; "
                        + "retiring execution environment",
                removed.getName(), identifier, phase, errorType, String.valueOf(port));

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
        // Reuses the same 500 that /event/next returns to any extension polling after a fault,
        // rather than inventing a separate response for the already-parked case: AWS specifies
        // that the environment is terminal but not what an open long-poll receives, so matching
        // the behaviour we already ship is the conservative choice.
        for (RoutingContext poller : strandedExtensionPollers) {
            vertx.runOnContext(v -> {
                if (!poller.response().ended()) {
                    sendExtensionFaulted(poller);
                }
            });
        }

        ctx.response()
                .setStatusCode(202)
                .putHeader("Content-Type", "application/json")
                .end(STATUS_OK_BODY);
    }
}
