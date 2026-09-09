package io.github.hectorvent.floci.services.eks;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Mints an IRSA service-account token signed by a cluster's OIDC key.
 *
 * <p>Real EKS has the kubelet project this token into a pod; Floci has no kubelet in the loop, so a
 * local-dev harness asks for one here, writes it to the file named by
 * {@code AWS_WEB_IDENTITY_TOKEN_FILE}, and the AWS SDK on the pod presents it to
 * {@code sts:AssumeRoleWithWebIdentity} exactly as it would in production.
 *
 * <p>Minting server-side keeps the RSA private key inside Floci. Callers never hold signing
 * material, and the claim shape stays authoritative in one place.
 */
@ApplicationScoped
@Path("_floci/eks/clusters/{name}/oidc-token")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksOidcTokenController {

    private final EksService eksService;
    private final EksOidcService oidcService;

    @Inject
    public EksOidcTokenController(EksService eksService, EksOidcService oidcService) {
        this.eksService = eksService;
        this.oidcService = oidcService;
    }

    @POST
    public Response mintToken(@PathParam("name") String clusterName, Map<String, Object> request) {
        Cluster cluster = eksService.describeCluster(clusterName);
        String issuer = cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                ? cluster.getIdentity().getOidc().getIssuer()
                : null;
        if (issuer == null || issuer.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Cluster " + clusterName + " has no OIDC issuer", 400);
        }

        Map<String, Object> body = request == null ? Map.of() : request;
        String namespace = string(body, "namespace");
        String serviceAccount = string(body, "serviceAccount");
        String audience = string(body, "audience");
        Integer lifetime = integer(body, "expirySeconds");

        String token = oidcService.mintServiceAccountToken(
                clusterName, issuer, namespace, serviceAccount, audience, lifetime);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("issuer", issuer);
        response.put("subject", "system:serviceaccount:" + namespace + ":" + serviceAccount);
        response.put("audience", audience == null || audience.isBlank()
                ? EksOidcService.STS_AUDIENCE : audience);
        return Response.ok(response).build();
    }

    private String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private Integer integer(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidParameterException",
                        key + " must be an integer", 400);
            }
        }
        return null;
    }
}
