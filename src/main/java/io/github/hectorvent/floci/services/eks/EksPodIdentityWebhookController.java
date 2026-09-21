package io.github.hectorvent.floci.services.eks;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Kubernetes mutating admission webhook for EKS Pod Identity on k3s-backed clusters.
 *
 * <p>The {@code MutatingWebhookConfiguration} that {@code EksClusterManager} drops into the
 * cluster's k3s manifests directory points here, scoped per cluster and account in the style of the
 * token webhook. The API server calls Floci with no AWS credentials, so the account has to come
 * from the URL.
 *
 * <p>Unlike the token webhook, which is configured through a kubeconfig and may be plain HTTP,
 * Kubernetes requires an {@code https} URL here, so the route is only registered when
 * {@code floci.tls.enabled} is on.
 *
 * <p>This is Floci plumbing under the {@code _floci/...} namespace, not an AWS API.
 */
@ApplicationScoped
@Path("_floci/eks/clusters/{clusterName}/pod-identity-webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksPodIdentityWebhookController {

    private final EksPodIdentityWebhook webhook;

    @Inject
    public EksPodIdentityWebhookController(EksPodIdentityWebhook webhook) {
        this.webhook = webhook;
    }

    @POST
    @Path("scope/{accountId}")
    public Response review(@PathParam("clusterName") String clusterName,
                           @PathParam("accountId") String accountId,
                           Map<String, Object> admissionReview) {
        return Response.ok(webhook.review(clusterName, accountId, admissionReview)).build();
    }
}
