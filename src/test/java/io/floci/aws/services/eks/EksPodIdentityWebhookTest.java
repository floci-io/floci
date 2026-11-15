package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.eks.model.Addon;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.PodIdentityAssociationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.floci.aws.testing.InlineMocks.inlineMock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class EksPodIdentityWebhookTest {

    private static final String CLUSTER_NAME = "demo";
    private static final String ACCOUNT_ID = "123456789012";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FLOCI_ENDPOINT = "http://floci.local:4566";

    private EksService eks;
    private EksAddonService addons;
    private EksPodIdentityAssociationService associations;
    private EksPodIdentityWebhook webhook;

    @BeforeEach
    void setUp() {
        eks = Mockito.mock(EksService.class);
        addons = Mockito.mock(EksAddonService.class);
        associations = Mockito.mock(EksPodIdentityAssociationService.class);
        webhook = new EksPodIdentityWebhook(eks, addons, associations, () -> FLOCI_ENDPOINT);

        Cluster cluster = new Cluster();
        cluster.setName(CLUSTER_NAME);
        when(eks.findPreRegionalCluster(ACCOUNT_ID, CLUSTER_NAME)).thenReturn(Optional.of(cluster));
        when(addons.describe(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON)))
                .thenReturn(inlineMock(Addon.class));
        when(associations.list(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new EksPodIdentityAssociationService.Page(
                        List.of(inlineMock(PodIdentityAssociationSummary.class)), null));
    }

    private Map<String, Object> admissionReview(Map<String, Object> pod) {
        return Map.of(
                "apiVersion", "admission.k8s.io/v1",
                "kind", "AdmissionReview",
                "request", Map.of("uid", "req-1", "namespace", "payments", "object", pod));
    }

    private Map<String, Object> pod(String serviceAccount, List<Object> containers) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceAccountName", serviceAccount);
        spec.put("containers", containers);
        return Map.of("kind", "Pod", "metadata", Map.of("name", "api"), "spec", spec);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(Map<String, Object> review) {
        return (Map<String, Object>) review.get("response");
    }

    private JsonNode patchOf(Map<String, Object> review) throws Exception {
        Object patch = response(review).get("patch");
        return patch == null ? null
                : MAPPER.readTree(Base64.getDecoder().decode((String) patch));
    }

    @Test
    void matchingServiceAccountIsPatched() throws Exception {
        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertEquals("req-1", response(review).get("uid"));
        assertEquals("JSONPatch", response(review).get("patchType"));
        assertEquals(3, patchOf(review).size());
        Mockito.verify(associations).list(any(), eq("payments"), eq("checkout"), any(), any());
    }

    /**
     * The exact shape an AWS SDK matches on: the environment variable names and values, the mount
     * path and the token audience. A near-miss silently does nothing, so this asserts the whole
     * patch rather than probing it. Init containers get the same treatment as containers.
     */
    @Test
    void patchMatchesTheAwsPodIdentityInjectionExactly() throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceAccountName", "checkout");
        spec.put("initContainers", List.of(Map.of("name", "migrate")));
        spec.put("containers", List.of(Map.of("name", "app")));
        Map<String, Object> pod = Map.of("kind", "Pod", "spec", spec);

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID, admissionReview(pod)));

        assertEquals(MAPPER.readTree("""
                [
                  {"op":"add","path":"/spec/volumes","value":[
                    {"name":"eks-pod-identity-token","projected":{"defaultMode":420,"sources":[
                      {"serviceAccountToken":{"audience":"pods.eks.amazonaws.com",
                       "expirationSeconds":86400,"path":"eks-pod-identity-token"}}]}}]},
                  {"op":"add","path":"/spec/initContainers/0/volumeMounts","value":[
                    {"name":"eks-pod-identity-token",
                     "mountPath":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount",
                     "readOnly":true}]},
                  {"op":"add","path":"/spec/initContainers/0/env","value":[
                    {"name":"AWS_CONTAINER_CREDENTIALS_FULL_URI",
                     "value":"http://169.254.170.23/v1/credentials"},
                    {"name":"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
                     "value":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token"},
                    {"name":"AWS_ENDPOINT_URL","value":"http://floci.local:4566"},
                    {"name":"AWS_ENDPOINT_URL_STS","value":"http://floci.local:4566"}]},
                  {"op":"add","path":"/spec/containers/0/volumeMounts","value":[
                    {"name":"eks-pod-identity-token",
                     "mountPath":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount",
                     "readOnly":true}]},
                  {"op":"add","path":"/spec/containers/0/env","value":[
                    {"name":"AWS_CONTAINER_CREDENTIALS_FULL_URI",
                     "value":"http://169.254.170.23/v1/credentials"},
                    {"name":"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
                     "value":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token"},
                    {"name":"AWS_ENDPOINT_URL","value":"http://floci.local:4566"},
                    {"name":"AWS_ENDPOINT_URL_STS","value":"http://floci.local:4566"}]}
                ]"""), patch);
    }

    @Test
    void podMatchingNoAssociationIsAdmittedUnchanged() throws Exception {
        when(associations.list(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new EksPodIdentityAssociationService.Page(List.of(), null));

        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("unassociated", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(patchOf(review));
        assertFalse(response(review).containsKey("patchType"));
    }

    @Test
    void clusterWithoutThePodIdentityAgentAddonIsNotPatched() throws Exception {
        when(addons.describe(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON)))
                .thenThrow(new AwsException("ResourceNotFoundException", "No addon", 404));

        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(patchOf(review));
        Mockito.verifyNoInteractions(associations);
    }

    /** The account is in the webhook URL because the API server calls Floci with no credentials. */
    @Test
    void clusterOwnedByAnotherAccountIsNotPatched() throws Exception {
        Map<String, Object> review = webhook.review(CLUSTER_NAME, "000000000000",
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(patchOf(review));
        Mockito.verifyNoInteractions(addons, associations);
    }

    @Test
    void malformedReviewIsAdmittedRatherThanRejected() throws Exception {
        for (Map<String, Object> malformed : malformedReviews()) {
            Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID, malformed);
            assertEquals(Boolean.TRUE, response(review).get("allowed"), () -> "rejected " + malformed);
            assertEquals("AdmissionReview", review.get("kind"));
            assertNull(patchOf(review));
        }
        assertEquals(Boolean.TRUE, response(webhook.review(CLUSTER_NAME, ACCOUNT_ID, null)).get("allowed"));
    }

    private static List<Map<String, Object>> malformedReviews() {
        List<Map<String, Object>> reviews = new ArrayList<>();
        reviews.add(Map.of());
        reviews.add(Map.of("request", "not-an-object"));
        reviews.add(Map.of("request", Map.of("uid", "req-1")));
        reviews.add(Map.of("request", Map.of("uid", "req-1", "namespace", "payments",
                "object", Map.of("kind", "Pod"))));
        return reviews;
    }

    /**
     * A route carrying the Region reviews against that Region's cluster. The Region-less route
     * resolves the cluster created before Regional names, which registered it (#7966).
     */
    @Test
    void aRouteWithARegionReviewsAgainstThatRegionsCluster() throws Exception {
        Cluster regional = new Cluster();
        regional.setName(CLUSTER_NAME);
        when(eks.findAuthenticationCluster(ACCOUNT_ID, "eu-west-1", CLUSTER_NAME)).thenReturn(Optional.of(regional));

        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID, "eu-west-1",
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(3, patchOf(review).size());
        Mockito.verify(associations).list(eq(regional), eq("payments"), eq("checkout"), any(), any());
        Mockito.verify(eks, Mockito.never()).findPreRegionalCluster(any(), any());
    }

    @Test
    void internalErrorStillAdmitsThePod() throws Exception {
        when(eks.findPreRegionalCluster(ACCOUNT_ID, CLUSTER_NAME))
                .thenThrow(new IllegalStateException("storage is gone"));

        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(patchOf(review));
    }

    /** The real webhook never overrides what the workload set itself. */
    @Test
    void entriesThePodAlreadyCarriesAreLeftAlone() throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceAccountName", "checkout");
        spec.put("volumes", List.of(Map.of("name", "eks-pod-identity-token")));
        spec.put("containers", List.of(Map.of(
                "name", "app",
                "volumeMounts", List.of(Map.of("name", "eks-pod-identity-token", "mountPath", "/elsewhere")),
                "env", List.of(Map.of("name", "AWS_CONTAINER_CREDENTIALS_FULL_URI", "value", "http://127.0.0.1/x")))));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(Map.of("kind", "Pod", "spec", spec))));

        assertEquals(3, patch.size());
        assertEquals("/spec/containers/0/env/-", patch.get(0).get("path").asText());
        assertEquals("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", patch.get(0).get("value").get("name").asText());
    }

    /** A workload that points its SDK somewhere itself keeps that choice, for STS too. */
    @Test
    void endpointTheContainerSetsItselfIsKept() throws Exception {
        Map<String, Object> container = Map.of(
                "name", "app",
                "env", List.of(Map.of("name", "AWS_ENDPOINT_URL", "value", "http://proxy:8080")));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(container)))));

        List<String> added = new ArrayList<>();
        for (JsonNode operation : patch) {
            if (operation.get("path").asText().equals("/spec/containers/0/env/-")) {
                added.add(operation.get("value").get("name").asText() + "=" + operation.get("value").get("value").asText());
            }
        }
        assertEquals(List.of(
                "AWS_CONTAINER_CREDENTIALS_FULL_URI=http://169.254.170.23/v1/credentials",
                "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE="
                        + "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token"), added);
    }

    /** What {@code envFrom} brings in is unknown at admission, and an injected env entry would win over it. */
    @Test
    void containerWithEnvFromGetsCredentialsButNoEndpoint() throws Exception {
        Map<String, Object> container = Map.of(
                "name", "app",
                "envFrom", List.of(Map.of("configMapRef", Map.of("name", "aws-endpoints"))));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(container)))));

        JsonNode env = patch.get(patch.size() - 1);
        assertEquals("/spec/containers/0/env", env.get("path").asText());
        assertEquals(MAPPER.readTree("""
                [{"name":"AWS_CONTAINER_CREDENTIALS_FULL_URI","value":"http://169.254.170.23/v1/credentials"},
                 {"name":"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
                  "value":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token"}]"""),
                env.get("value"));
    }

    /** A pod resolves neither the cluster container's /etc/hosts nor Floci's DNS, so only an IP works. */
    @Test
    void endpointIsOnlyInjectedWhereAPodCanResolveIt() throws Exception {
        assertEquals("http://172.18.0.5:4566",
                EksPodIdentityWebhook.podReachableEndpoint("http://172.18.0.5:4566"));
        assertNull(EksPodIdentityWebhook.podReachableEndpoint("http://localhost.floci.io:4566"));
        assertNull(EksPodIdentityWebhook.podReachableEndpoint("http://host.docker.internal:4566"));

        webhook = new EksPodIdentityWebhook(eks, addons, associations, () -> null);
        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        assertEquals(2, patch.get(patch.size() - 1).get("value").size(), "credentials only");
    }

    @Test
    void responseEchoesTheRequestApiVersion() {
        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID, Map.of(
                "apiVersion", "admission.k8s.io/v1beta1",
                "request", Map.of("uid", "req-9")));

        assertEquals("admission.k8s.io/v1beta1", review.get("apiVersion"));
        assertTrue(response(review).containsKey("uid"));
    }
}
