package io.floci.aws.lifecycle;

import io.floci.aws.config.FlociVersion;
import io.floci.aws.core.common.ServiceRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthController {

    /** The {@code cloud} this emulator names itself with in its health and info bodies. */
    static final String CLOUD = "aws";

    private final ServiceRegistry serviceRegistry;
    private final String version;

    @Inject
    public HealthController(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
        this.version = FlociVersion.current();
    }

    @GET
    public Response health() {
        return Response.ok(body(serviceRegistry.getServices(), version)).build();
    }

    /**
     * The health body of the Floci control-plane contract, shared by bare {@code /health} and
     * {@code /_floci/health} so the two cannot drift. {@code status}, {@code cloud}, {@code services}
     * and {@code version} are the fields every Floci emulator serves; {@code edition} and
     * {@code original_edition} are this emulator's own and stay as they always were. Floci has no
     * failure signal of its own, so {@code status} is always {@code UP}.
     */
    static Map<String, Object> body(Map<String, String> services, String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("cloud", CLOUD);
        body.put("services", services);
        body.put("edition", "community");
        body.put("original_edition", "floci-always-free");
        body.put("version", version);
        return body;
    }
}
