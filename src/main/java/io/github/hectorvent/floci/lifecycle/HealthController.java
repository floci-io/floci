package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.core.common.ServiceRegistry;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Internal health endpoint at /_floci/health.
 * Returns the Floci version and the status of each enabled service.
 * Compatible with the LocalStack /_localstack/health pattern.
 */
@Path("{path:(_floci|_localstack)/health}")
@Produces(MediaType.APPLICATION_JSON)
public class HealthController {

    private final ServiceRegistry serviceRegistry;
    private final String version;

    public HealthController(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.version = resolveVersion();
    }

    @GET
    public Response health() {
        return Response.ok(Map.of("services", serviceRegistry.getServices(), "edition", "floci-always-free", "version", version)).build();
    }

    private static String resolveVersion() {
        String env = System.getenv("FLOCI_VERSION");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "dev";
    }
}
