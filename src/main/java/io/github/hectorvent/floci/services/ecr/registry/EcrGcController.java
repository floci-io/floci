package io.github.hectorvent.floci.services.ecr.registry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Admin endpoint that triggers garbage collection on the backing {@code registry:2}
 * container to reclaim disk space after {@code BatchDeleteImage} calls.
 */
@ApplicationScoped
@Path("_floci/ecr/gc")
@Produces(MediaType.APPLICATION_JSON)
public class EcrGcController {

    private static final Logger LOG = Logger.getLogger(EcrGcController.class);
    private static final int GC_TIMEOUT_SECONDS = 120;

    private final EcrRegistryManager registryManager;

    @Inject
    public EcrGcController(EcrRegistryManager registryManager) {
        this.registryManager = registryManager;
    }

    @POST
    public Response runGc() {
        if (!registryManager.isStarted()) {
            return Response.status(400)
                    .entity(Map.of(
                            "status", "error",
                            "output", "ECR registry is not started",
                            "durationMs", 0))
                    .build();
        }

        try {
            EcrRegistryManager.GcResult result = registryManager.runGarbageCollect(GC_TIMEOUT_SECONDS);
            return Response.ok(Map.of(
                            "status", "ok",
                            "output", result.output(),
                            "durationMs", result.durationMs()))
                    .build();
        } catch (Exception e) {
            LOG.warnv("ECR GC failed: {0}", e.getMessage());
            return Response.status(500)
                    .entity(Map.of(
                            "status", "error",
                            "output", e.getMessage() != null ? e.getMessage() : "",
                            "durationMs", 0))
                    .build();
        }
    }
}
