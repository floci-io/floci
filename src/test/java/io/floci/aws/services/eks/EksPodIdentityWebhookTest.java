package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.PodIdentityAssociationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.InlineMocks.inlineMock;
import static org.awaitility.Awaitility.await;
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
    private final Map<String, EksClusterManager.ServiceAccountRead> serviceAccounts = new ConcurrentHashMap<>();
    private final List<String> serviceAccountReads = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-24T00:00:00Z"));
    private volatile CountDownLatch readGate;

    private EksPodIdentityWebhook webhook(String endpoint) {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        return new EksPodIdentityWebhook(eks, addons, associations, () -> endpoint,
                (cluster, namespace, name) -> {
                    serviceAccountReads.add(namespace + "/" + name);
                    CountDownLatch gate = readGate;
                    if (gate != null) {
                        try {
                            gate.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return serviceAccounts.getOrDefault(namespace + "/" + name,
                            EksClusterManager.ServiceAccountRead.absent());
                },
                cluster -> "eu-west-1", clock, Duration.ofMillis(500));
    }

    @BeforeEach
    void setUp() {
        eks = Mockito.mock(EksService.class);
        addons = Mockito.mock(EksAddonService.class);
        associations = Mockito.mock(EksPodIdentityAssociationService.class);
        webhook = webhook(FLOCI_ENDPOINT);

        Cluster cluster = new Cluster();
        cluster.setName(CLUSTER_NAME);
        when(eks.findPreRegionalCluster(ACCOUNT_ID, CLUSTER_NAME)).thenReturn(Optional.of(cluster));
        when(addons.isInstalled(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON))).thenReturn(true);
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
        when(addons.isInstalled(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON))).thenReturn(false);

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

        webhook = webhook(null);
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

    // ------------------------------------------------------------
    // IAM roles for service accounts
    // ------------------------------------------------------------

    private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/payments";

    private void noPodIdentity() {
        when(associations.list(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new EksPodIdentityAssociationService.Page(List.of(), null));
    }

    private void serviceAccount(String name, Map<String, String> annotations) throws Exception {
        serviceAccounts.put("payments/" + name, EksClusterManager.ServiceAccountRead.found(
                MAPPER.writeValueAsString(Map.of("apiVersion", "v1", "kind", "ServiceAccount",
                        "metadata", Map.of("name", name, "namespace", "payments", "annotations", annotations)))));
    }

    private Map<String, Object> reviewOf(String serviceAccount) {
        return webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod(serviceAccount, List.of(Map.of("name", "app")))));
    }

    private Map<String, Object> annotatedPod(String serviceAccount, Map<String, String> annotations,
                                             Map<String, Object> spec) {
        Map<String, Object> withAccount = new LinkedHashMap<>(spec);
        withAccount.put("serviceAccountName", serviceAccount);
        return Map.of("kind", "Pod", "metadata", Map.of("name", "api", "annotations", annotations),
                "spec", withAccount);
    }

    /**
     * The upstream webhook's mutation, value for value: the projected token for STS, its read-only mount, the role
     * and token file, regional STS endpoints, and the cluster's Region, on init containers and containers alike.
     */
    @Test
    void aServiceAccountWithARoleArnGetsTheWebIdentityMutation() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("initContainers", List.of(Map.of("name", "migrate")));
        spec.put("containers", List.of(Map.of("name", "app")));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(annotatedPod("checkout", Map.of(), spec))));

        String env = """
                [{"name":"AWS_ROLE_ARN","value":"arn:aws:iam::123456789012:role/payments"},
                 {"name":"AWS_WEB_IDENTITY_TOKEN_FILE",
                  "value":"/var/run/secrets/eks.amazonaws.com/serviceaccount/token"},
                 {"name":"AWS_STS_REGIONAL_ENDPOINTS","value":"regional"},
                 {"name":"AWS_DEFAULT_REGION","value":"eu-west-1"},
                 {"name":"AWS_REGION","value":"eu-west-1"},
                 {"name":"AWS_ENDPOINT_URL","value":"http://floci.local:4566"},
                 {"name":"AWS_ENDPOINT_URL_STS","value":"http://floci.local:4566"}]""";
        String mount = """
                [{"name":"aws-iam-token","mountPath":"/var/run/secrets/eks.amazonaws.com/serviceaccount",
                  "readOnly":true}]""";
        assertEquals(MAPPER.readTree("""
                [
                  {"op":"add","path":"/spec/volumes","value":[
                    {"name":"aws-iam-token","projected":{"sources":[{"serviceAccountToken":
                      {"audience":"sts.amazonaws.com","expirationSeconds":86400,"path":"token"}}]}}]},
                  {"op":"add","path":"/spec/initContainers/0/volumeMounts","value":%1$s},
                  {"op":"add","path":"/spec/initContainers/0/env","value":%2$s},
                  {"op":"add","path":"/spec/containers/0/volumeMounts","value":%1$s},
                  {"op":"add","path":"/spec/containers/0/env","value":%2$s}
                ]""".formatted(mount, env)), patch);
        assertEquals(List.of("payments/checkout"), serviceAccountReads);
    }

    /** Pod Identity takes precedence, as the upstream webhook's container credentials do; one mutation, never both. */
    @Test
    void podIdentityTakesPrecedenceOverARoleArn() throws Exception {
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        assertEquals("eks-pod-identity-token", patch.get(0).get("value").get(0).get("name").asText());
        assertFalse(patch.toString().contains("AWS_ROLE_ARN"), patch.toString());
        assertEquals(List.of(), serviceAccountReads);
    }

    /** Without the agent there is no Pod Identity mutation, so the role ARN applies. */
    @Test
    void aRoleArnAppliesWhenThePodIdentityAgentIsNotInstalled() throws Exception {
        when(addons.isInstalled(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON))).thenReturn(false);
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        assertEquals("aws-iam-token", patch.get(0).get("value").get(0).get("name").asText());
    }

    @Test
    void aServiceAccountWithoutARoleArnOrThatCannotBeReadLeavesThePodUnchanged() throws Exception {
        noPodIdentity();
        serviceAccount("plain", Map.of("team", "payments"));
        serviceAccounts.put("payments/broken", EksClusterManager.ServiceAccountRead.found("not json"));
        serviceAccounts.put("payments/unreadable", EksClusterManager.ServiceAccountRead.failed());

        for (String account : List.of("plain", "broken", "missing", "unreadable")) {
            Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                    admissionReview(pod(account, List.of(Map.of("name", "app")))));

            assertEquals(Boolean.TRUE, response(review).get("allowed"));
            assertNull(response(review).get("patch"), account);
        }
    }

    @Test
    void theServiceAccountAndThePodAnnotationsShapeTheToken() throws Exception {
        assertEquals(Optional.of(new EksPodIdentityWebhook.WebIdentity(ROLE_ARN, "custom", 3600, false)),
                EksPodIdentityWebhook.webIdentity(Map.of("eks.amazonaws.com/role-arn", ROLE_ARN,
                        "eks.amazonaws.com/audience", "custom", "eks.amazonaws.com/token-expiration", "7200",
                        "eks.amazonaws.com/sts-regional-endpoints", "false"),
                        Map.of("eks.amazonaws.com/token-expiration", "3600")));
        assertEquals(Optional.of(new EksPodIdentityWebhook.WebIdentity(ROLE_ARN, "sts.amazonaws.com", 600, true)),
                EksPodIdentityWebhook.webIdentity(Map.of("eks.amazonaws.com/role-arn", ROLE_ARN,
                        "eks.amazonaws.com/token-expiration", "60", "eks.amazonaws.com/sts-regional-endpoints",
                        "maybe"), Map.of()));
        assertEquals(Optional.of(new EksPodIdentityWebhook.WebIdentity(ROLE_ARN, "sts.amazonaws.com", 7200, true)),
                EksPodIdentityWebhook.webIdentity(Map.of("eks.amazonaws.com/role-arn", ROLE_ARN,
                        "eks.amazonaws.com/token-expiration", "7200"),
                        Map.of("eks.amazonaws.com/token-expiration", "soon")));
        assertEquals(Optional.of(new EksPodIdentityWebhook.WebIdentity(ROLE_ARN, "sts.amazonaws.com", 86400, true)),
                EksPodIdentityWebhook.webIdentity(Map.of("eks.amazonaws.com/role-arn", ROLE_ARN,
                        "eks.amazonaws.com/token-expiration", "a day"), Map.of()));
        assertEquals(Optional.empty(), EksPodIdentityWebhook.webIdentity(
                Map.of("eks.amazonaws.com/role-arn", " "), Map.of()));
    }

    @Test
    void theTokenTakesTheAnnotatedLifetime() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN,
                "eks.amazonaws.com/audience", "sts.amazonaws.com", "eks.amazonaws.com/sts-regional-endpoints", "false"));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID, admissionReview(annotatedPod("checkout",
                Map.of("eks.amazonaws.com/token-expiration", "3600"), Map.of("containers",
                        List.of(Map.of("name", "app")))))));

        assertEquals(MAPPER.readTree("""
                {"audience":"sts.amazonaws.com","expirationSeconds":3600,"path":"token"}"""),
                patch.get(0).get("value").get(0).get("projected").get("sources").get(0).get("serviceAccountToken"));
        assertFalse(patch.toString().contains("AWS_STS_REGIONAL_ENDPOINTS"), patch.toString());
    }

    /** Floci's STS exchanges a cluster's tokens for sts.amazonaws.com alone, so another audience could never work. */
    @Test
    void anAudienceFlociCannotExchangeLeavesThePodUnchanged() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN,
                "eks.amazonaws.com/audience", "custom"));

        assertNull(response(reviewOf("checkout")).get("patch"));
    }

    /** A token volume or mount of the same name the pod brings, which would not hold the token, gets no role pair. */
    @Test
    void aTokenVolumeOrMountThatWouldNotHoldTheTokenGetsNoRolePair() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));
        Map<String, Object> elsewhere = Map.of("name", "app",
                "volumeMounts", List.of(Map.of("name", "aws-iam-token", "mountPath", "/elsewhere")));
        Map<String, Object> inPlace = Map.of("name", "sidecar",
                "volumeMounts", List.of(Map.of("name", "aws-iam-token",
                        "mountPath", "/var/run/secrets/eks.amazonaws.com/serviceaccount")));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("containers", List.of(elsewhere, inPlace));

        JsonNode mounted = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(annotatedPod("checkout", Map.of(), spec))));
        assertFalse(envOf(mounted, 0).contains("AWS_ROLE_ARN"), mounted.toString());
        assertTrue(envOf(mounted, 1).contains("AWS_ROLE_ARN"), mounted.toString());

        spec.put("volumes", List.of(Map.of("name", "aws-iam-token", "emptyDir", Map.of())));
        spec.put("containers", List.of(Map.of("name", "app")));
        JsonNode other = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(annotatedPod("checkout", Map.of(), spec))));
        assertFalse(envOf(other, 0).contains("AWS_ROLE_ARN"), other.toString());
        assertTrue(envOf(other, 0).contains("AWS_REGION"), other.toString());
    }

    private static String envOf(JsonNode patch, int container) {
        StringBuilder env = new StringBuilder();
        patch.forEach(operation -> {
            if (operation.get("path").asText().startsWith("/spec/containers/" + container + "/env")) {
                env.append(operation.get("value"));
            }
        });
        return env.toString();
    }

    /** A burst of pods, and the cluster's own pods of service accounts with no role, cost one read each. */
    @Test
    void aServiceAccountIsReadOnceWhileItsReadIsFresh() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));

        reviewOf("checkout");
        reviewOf("checkout");
        reviewOf("missing");
        reviewOf("missing");
        assertEquals(List.of("payments/checkout", "payments/missing"), serviceAccountReads);

        now.set(now.get().plus(EksPodIdentityWebhook.SERVICE_ACCOUNT_CACHE_TTL));
        assertTrue(response(reviewOf("checkout")).containsKey("patch"));
        assertEquals(List.of("payments/checkout", "payments/missing", "payments/checkout"), serviceAccountReads);
    }

    @Test
    void aFailedReadIsNotReused() throws Exception {
        noPodIdentity();
        serviceAccounts.put("payments/checkout", EksClusterManager.ServiceAccountRead.failed());
        reviewOf("checkout");
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));
            return response(reviewOf("checkout")).containsKey("patch");
        });

        assertEquals("payments/checkout", serviceAccountReads.get(1));
    }

    /** A read that hangs, in Docker before kubectl even starts, still leaves the webhook answering in time. */
    @Test
    void aReadThatOutlastsTheLookupTimeoutLeavesThePodUnchangedAndServesLaterPods() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));
        readGate = new CountDownLatch(1);

        Map<String, Object> review = reviewOf("checkout");
        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(response(review).get("patch"));

        readGate.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> response(reviewOf("checkout")).containsKey("patch"));
        assertEquals(List.of("payments/checkout"), serviceAccountReads);
    }


    @Test
    void aSkippedContainerIsLeftAsItIs() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("initContainers", List.of(Map.of("name", "migrate")));
        spec.put("containers", List.of(Map.of("name", "app"), Map.of("name", "proxy")));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID, admissionReview(annotatedPod("checkout",
                Map.of("eks.amazonaws.com/skip-containers", "migrate, proxy"), spec))));

        List<String> paths = new ArrayList<>();
        patch.forEach(operation -> paths.add(operation.get("path").asText()));
        assertEquals(List.of("/spec/volumes", "/spec/containers/0/volumeMounts", "/spec/containers/0/env"), paths);
    }

    /** As upstream, a container setting either variable of a pair gets neither, and nothing it set is overwritten. */
    @Test
    void variablesTheContainerSetsAreLeftAlone() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));
        Map<String, Object> container = Map.of("name", "app",
                "env", List.of(Map.of("name", "AWS_WEB_IDENTITY_TOKEN_FILE", "value", "/mine"),
                        Map.of("name", "AWS_DEFAULT_REGION", "value", "us-west-2"),
                        Map.of("name", "AWS_STS_REGIONAL_ENDPOINTS", "value", "legacy"),
                        Map.of("name", "AWS_ENDPOINT_URL", "value", "http://mine")),
                "volumeMounts", List.of(Map.of("name", "aws-iam-token",
                        "mountPath", "/var/run/secrets/eks.amazonaws.com/serviceaccount")));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(container)))));

        assertEquals(1, patch.size(), patch.toString());
        assertEquals("/spec/volumes", patch.get(0).get("path").asText());
    }

    @Test
    void anExistingEnvListIsAppendedTo() throws Exception {
        noPodIdentity();
        serviceAccount("checkout", Map.of("eks.amazonaws.com/role-arn", ROLE_ARN));
        Map<String, Object> container = Map.of("name", "app",
                "env", List.of(Map.of("name", "AWS_REGION", "value", "us-west-2")));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(container)))));

        List<String> appended = new ArrayList<>();
        patch.forEach(operation -> {
            if (operation.get("path").asText().equals("/spec/containers/0/env/-")) {
                appended.add(operation.get("value").get("name").asText());
            }
        });
        assertEquals(List.of("AWS_ROLE_ARN", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_STS_REGIONAL_ENDPOINTS",
                "AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_STS"), appended);
    }
}
