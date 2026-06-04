package io.github.hectorvent.floci.services.lambda.runtime;

import io.github.hectorvent.floci.core.common.port.PortAllocator;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Creates RuntimeApiServer instances, each on a unique port.
 */
@ApplicationScoped
public class RuntimeApiServerFactory {

    private static final Logger LOG = Logger.getLogger(RuntimeApiServerFactory.class);

    private final Vertx vertx;
    private final PortAllocator portAllocator;

    @Inject
    public RuntimeApiServerFactory(Vertx vertx, PortAllocator portAllocator) {
        this.vertx = vertx;
        this.portAllocator = portAllocator;
    }

    public RuntimeApiServer create() {
        int port = portAllocator.allocate();
        try {
            RuntimeApiServer server = new RuntimeApiServer(vertx, port);
            server.start().get(10, TimeUnit.SECONDS);
            LOG.debugv("Created RuntimeApiServer on port {0}", String.valueOf(port));
            return server;
        } catch (InterruptedException e) {
            portAllocator.release(port);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while starting RuntimeApiServer", e);
        } catch (ExecutionException | TimeoutException e) {
            portAllocator.release(port);
            throw new RuntimeException("Failed to start RuntimeApiServer on port " + port, e);
        }
    }

    public void release(RuntimeApiServer server) {
        portAllocator.release(server.getPort());
    }
}
