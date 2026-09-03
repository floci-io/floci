package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksTokenWebhookControllerTest {

    private static final String CLUSTER_NAME = "demo";
    private static final String ACCESS_KEY_ID = "AKIDEKSTEST";
    private static final String SECRET_ACCESS_KEY = "eks-secret-key";
    private final IamService iamService = IamServiceTestHelper.iamServiceWithAccessKey(
            ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    private final EksTokenWebhookController controller = new EksTokenWebhookController(
            new EksTokenValidator(iamService));

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
    void awsIamTokenAuthenticatesAsClusterAdmin() throws Exception {
        Response response = controller.review(CLUSTER_NAME, tokenReview(validToken()));

        Map<String, Object> status = status(response);
        assertEquals(Boolean.TRUE, status.get("authenticated"));

        Map<String, Object> user = (Map<String, Object>) status.get("user");
        assertEquals(List.of("system:masters"), user.get("groups"));
    }

    @Test
    void unrecognisedTokenIsRejected() {
        Response response = controller.review(CLUSTER_NAME, tokenReview("some-random-bearer-token"));
        assertEquals(Boolean.FALSE, status(response).get("authenticated"));
    }

    @Test
    void emptyOrMalformedReviewIsRejected() {
        assertFalse((Boolean) status(controller.review(CLUSTER_NAME, Map.of())).get("authenticated"));
        assertFalse((Boolean) status(controller.review(CLUSTER_NAME,
                Map.of("spec", Map.of()))).get("authenticated"));
    }

    @Test
    void responseIsAlwaysAWellFormedTokenReview() throws Exception {
        Response response = controller.review(CLUSTER_NAME, tokenReview(validToken()));
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("authentication.k8s.io/v1", body.get("apiVersion"));
        assertEquals("TokenReview", body.get("kind"));
        assertTrue(body.containsKey("status"));
    }

    @Test
    void responseEchoesRequestApiVersion() throws Exception {
        // The kube-apiserver defaults to the v1beta1 webhook API and cannot convert a v1
        // response back to v1beta1, so the response apiVersion MUST match the request's.
        Map<String, Object> v1beta1Review = Map.of(
                "apiVersion", "authentication.k8s.io/v1beta1",
                "kind", "TokenReview",
                "spec", Map.of("token", validToken()));

        Response response = controller.review(CLUSTER_NAME, v1beta1Review);
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("authentication.k8s.io/v1beta1", body.get("apiVersion"));
        assertEquals(Boolean.TRUE, status(response).get("authenticated"));
    }

    @Test
    void tokenBoundToAnotherClusterIsRejected() throws Exception {
        String token = SigV4TokenTestHelper.createEksToken(
                "other-cluster", ACCESS_KEY_ID, SECRET_ACCESS_KEY, Instant.now(), 60);

        Response response = controller.review(CLUSTER_NAME, tokenReview(token));

        assertEquals(Boolean.FALSE, status(response).get("authenticated"));
    }

    private String validToken() throws Exception {
        return SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, ACCESS_KEY_ID, SECRET_ACCESS_KEY, Instant.now(), 60);
    }
}
