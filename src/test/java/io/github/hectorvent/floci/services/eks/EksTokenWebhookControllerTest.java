package io.github.hectorvent.floci.services.eks;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksTokenWebhookControllerTest {

    private final EksTokenWebhookController controller = new EksTokenWebhookController();

    @SuppressWarnings("unchecked")
    private Map<String, Object> status(Response response) {
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        return (Map<String, Object>) body.get("status");
    }

    private Map<String, Object> tokenReview(String token) {
        return Map.of(
                "apiVersion", "authentication.k8s.io/v1",
                "kind", "TokenReview",
                "spec", Map.of("token", token));
    }

    @Test
    @SuppressWarnings("unchecked")
    void awsIamTokenAuthenticatesAsClusterAdmin() {
        Response response = controller.review(tokenReview("k8s-aws-v1.aHR0cHM6Ly9zdHM..."));

        Map<String, Object> status = status(response);
        assertEquals(Boolean.TRUE, status.get("authenticated"));

        Map<String, Object> user = (Map<String, Object>) status.get("user");
        assertEquals(List.of("system:masters"), user.get("groups"));
    }

    @Test
    void unrecognisedTokenIsRejected() {
        Response response = controller.review(tokenReview("some-random-bearer-token"));
        assertEquals(Boolean.FALSE, status(response).get("authenticated"));
    }

    @Test
    void emptyOrMalformedReviewIsRejected() {
        assertFalse((Boolean) status(controller.review(Map.of())).get("authenticated"));
        assertFalse((Boolean) status(controller.review(
                Map.of("spec", Map.of()))).get("authenticated"));
    }

    @Test
    void responseIsAlwaysAWellFormedTokenReview() {
        Response response = controller.review(tokenReview("k8s-aws-v1.abc"));
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("authentication.k8s.io/v1", body.get("apiVersion"));
        assertEquals("TokenReview", body.get("kind"));
        assertTrue(body.containsKey("status"));
    }

    @Test
    void responseEchoesRequestApiVersion() {
        // The kube-apiserver defaults to the v1beta1 webhook API and cannot convert a v1
        // response back to v1beta1 — the response apiVersion MUST match the request's.
        Map<String, Object> v1beta1Review = Map.of(
                "apiVersion", "authentication.k8s.io/v1beta1",
                "kind", "TokenReview",
                "spec", Map.of("token", "k8s-aws-v1.abc"));

        Response response = controller.review(v1beta1Review);
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("authentication.k8s.io/v1beta1", body.get("apiVersion"));
        assertEquals(Boolean.TRUE, status(response).get("authenticated"));
    }
}
