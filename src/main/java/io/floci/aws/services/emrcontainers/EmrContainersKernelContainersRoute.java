package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;

/**
 * Where an endpoint's gateway asks Floci for the container of a kernel that the endpoint's
 * {@code jupyter-kernel-overrides} gives an image of its own, and for its end ({@link EmrContainersKernelContainers}):
 * {@code POST} and {@code DELETE} on {@link #PATH}{@code <endpointId>/<kernelId>}, with the secret Floci gave that
 * gateway in {@link EmrContainersGateways#SECRET_HEADER}. Floci's own: on EMR on EKS the service runs a kernel's pod
 * itself.
 */
@ApplicationScoped
public class EmrContainersKernelContainersRoute {

    static final String PATH = "/_aws/emr-containers/kernel-containers/";

    private static final Logger LOG = Logger.getLogger(EmrContainersKernelContainersRoute.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    // A start's body: a kernel's name, a response address, a public key and the kernel's KERNEL_ variables.
    private static final long MAX_BODY = 64 * 1024;

    private final EmrContainersService service;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;

    @Inject
    public EmrContainersKernelContainersRoute(EmrContainersService service, ServiceConfigAccess serviceConfigAccess,
                                              Vertx vertx) {
        this.service = service;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
    }

    void register(@Observes Router router) {
        router.route(PATH + ":endpointId/:kernelId").handler(this::handle);
    }

    void handle(RoutingContext context) {
        // This route runs ahead of the filter that refuses a disabled service, so it checks for itself.
        if (!serviceConfigAccess.isEnabled(EmrContainersService.SERVICE)) {
            context.next();
            return;
        }
        HttpServerRequest request = context.request();
        String endpointId = context.pathParam("endpointId");
        String kernelId = context.pathParam("kernelId");
        String secret = request.getHeader(EmrContainersGateways.SECRET_HEADER);
        if (request.method() == HttpMethod.DELETE) {
            vertx.<EmrContainersService.KernelContainerRefusal>executeBlocking(
                    () -> service.stopKernelContainer(endpointId, kernelId, secret), false)
                    .onComplete(stopped -> {
                        if (stopped.failed()) {
                            LOG.warnv(stopped.cause(), "Kernel {0}'s container could not be stopped", kernelId);
                            answer(request, 500, "The kernel's container could not be stopped.");
                        } else if (stopped.result() == null) {
                            request.response().setStatusCode(204).end();
                        } else {
                            answer(request, stopped.result().status(), stopped.result().message());
                        }
                    });
            return;
        }
        if (request.method() != HttpMethod.POST) {
            answer(request, 405, "A kernel's container is started with POST and stopped with DELETE.");
            return;
        }
        String length = request.getHeader("content-length");
        if (length == null || !length.matches("[0-9]{1,6}") || Long.parseLong(length) > MAX_BODY) {
            answer(request, 400, "The request's body has no length, or too long a one.");
            return;
        }
        // Quarkus hands the route the request paused, and resumes it for a handler set on it but not for body().
        Future<Buffer> received = request.body();
        request.resume();
        received.onComplete(read -> {
            JsonNode body;
            try {
                body = read.succeeded() ? JSON.readTree(read.result().getBytes()) : null;
            } catch (IOException e) {
                body = null;
            }
            if (body == null || !body.isObject()) {
                answer(request, 400, "The request's body is no JSON object.");
                return;
            }
            JsonNode start = body;
            vertx.<EmrContainersService.KernelContainerRefusal>executeBlocking(
                    () -> service.startKernelContainer(endpointId, kernelId, secret, start), false)
                    .onComplete(started -> {
                        if (started.failed()) {
                            LOG.warnv(started.cause(), "Kernel {0}'s container could not be started", kernelId);
                            answer(request, 500, "The kernel's container could not be started.");
                        } else if (started.result() == null) {
                            answer(request, 201, "The kernel's container is starting.");
                        } else {
                            answer(request, started.result().status(), started.result().message());
                        }
                    });
        });
    }

    private static void answer(HttpServerRequest request, int status, String message) {
        request.response().setStatusCode(status).putHeader("content-type", "application/json")
                .end(new JsonObject().put("message", message).encode());
    }
}
