package io.github.hectorvent.floci.services.eks;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes token-authentication webhook for k3s-backed EKS clusters.
 *
 * <p>The k3s API server is configured (see {@code EksClusterManager}) to POST a
 * {@code TokenReview} here whenever it receives a bearer token it does not recognise — notably the
 * {@code k8s-aws-v1.<presigned-sts-url>} token produced by {@code aws eks get-token}. Floci accepts
 * the token and maps it to the {@code system:masters} group, which is bound to {@code cluster-admin}
 * by default. This is what makes the native {@code aws eks update-kubeconfig} + {@code kubectl}
 * workflow authenticate against a Floci EKS cluster.
 *
 * <p>This is Floci plumbing under the {@code _floci/...} namespace, not an AWS API.
 */
@ApplicationScoped
@Path("_floci/eks/token-webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksTokenWebhookController {

    private static final Logger LOG = Logger.getLogger(EksTokenWebhookController.class);
    private static final String EKS_TOKEN_PREFIX = "k8s-aws-v1.";

    @POST
    public Response review(Map<String, Object> tokenReview) {
        // The response apiVersion MUST match the request's (the kube-apiserver sends v1beta1 by
        // default and cannot convert a v1 response back). Echo whatever the apiserver sent.
        String apiVersion = tokenReview != null && tokenReview.get("apiVersion") instanceof String v
                ? v : "authentication.k8s.io/v1";

        String token = extractToken(tokenReview);
        boolean authenticated = token != null && token.startsWith(EKS_TOKEN_PREFIX);

        if (authenticated) {
            LOG.debug("EKS token-webhook: authenticated aws-iam token as cluster-admin");
            return Response.ok(Map.of(
                    "apiVersion", apiVersion,
                    "kind", "TokenReview",
                    "status", Map.of(
                            "authenticated", true,
                            "user", Map.of(
                                    "username", "floci:aws-iam",
                                    "uid", "floci-aws-iam",
                                    "groups", List.of("system:masters"))))).build();
        }

        LOG.debug("EKS token-webhook: rejecting unrecognised token");
        return Response.ok(Map.of(
                "apiVersion", apiVersion,
                "kind", "TokenReview",
                "status", Map.of("authenticated", false))).build();
    }

    @SuppressWarnings("unchecked")
    private String extractToken(Map<String, Object> tokenReview) {
        if (tokenReview == null) {
            return null;
        }
        Object spec = tokenReview.get("spec");
        if (spec instanceof Map<?, ?> specMap) {
            Object token = ((Map<String, Object>) specMap).get("token");
            if (token instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
