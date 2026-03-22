package io.github.hectorvent.floci;

import io.github.hectorvent.floci.core.common.ServiceRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoints.
 *
 * Provides both a native /health endpoint and a LocalStack-compatible
 * /_localstack/health endpoint so existing tooling (docker-compose
 * healthchecks, awslocal, start_codespace.sh) works without changes.
 */
@Path("")
public class HealthController {

    @Inject
    ServiceRegistry serviceRegistry;

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return buildHealthResponse();
    }

    @GET
    @Path("/_localstack/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response localstackHealth() {
        return buildHealthResponse();
    }

    private Response buildHealthResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "running");
        response.put("engine", "floci");
        Map<String, String> services = new HashMap<>();
        for (String svc : serviceRegistry.getEnabledServices()) {
            services.put(svc, "available");
        }
        response.put("services", services);
        return Response.ok(response).build();
    }
}
