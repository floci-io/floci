package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.RegistryHostConfig;
import io.github.hectorvent.floci.services.eks.model.RegistryHostsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Lets a caller supply containerd registry host configuration for a cluster: mirrors, TLS, and
 * request headers, none of which the generated {@code registries.yaml} can express past mirror
 * endpoints and basic TLS/auth. Configuring a host here does not require deleting or replacing
 * the ECR mirror Floci already generates; see {@link EksClusterManager#injectRegistryHosts} and
 * {@link EksClusterManager#buildRegistriesYaml} for how the two coexist and how a name collision
 * is resolved.
 *
 * <p>This is Floci plumbing under the {@code _floci/...} namespace, not an AWS API.
 */
@ApplicationScoped
@Path("_floci/eks/clusters/{name}/registry-hosts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksRegistryHostsController {

    private final EksService eksService;

    @Inject
    public EksRegistryHostsController(EksService eksService) {
        this.eksService = eksService;
    }

    @PUT
    public Response put(@PathParam("name") String clusterName, RegistryHostsConfig request) {
        List<RegistryHostConfig> hosts = request == null ? null : request.hosts();
        Cluster cluster = eksService.setRegistryHosts(clusterName, hosts);
        return Response.ok(new RegistryHostsConfig(cluster.getRegistryHosts())).build();
    }

    @GET
    public Response get(@PathParam("name") String clusterName) {
        return Response.ok(new RegistryHostsConfig(eksService.getRegistryHosts(clusterName))).build();
    }
}
