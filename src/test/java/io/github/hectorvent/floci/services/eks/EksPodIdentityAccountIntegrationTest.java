package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EksPodIdentityAccountIntegrationTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "123456789012";
    private static final String NAMESPACE = "payments";
    private static final String SERVICE_ACCOUNT = "checkout";

    @Inject
    EksService eksService;

    @Inject
    EksOidcService oidcService;

    @Inject
    EksAddonService addonService;

    @Inject
    EksPodIdentityAssociationService associationService;

    @Inject
    IamService iamService;

    @Test
    void anonymousPodIdentityCallsUseTheClustersAccount() {
        String name = "pi-account-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "role-" + name;
        try {
            Cluster defaultCluster = createCluster(DEFAULT_ACCOUNT, name);
            Cluster otherCluster = createCluster(OTHER_ACCOUNT, name);
            createPodIdentityResources(DEFAULT_ACCOUNT, defaultCluster, roleName);

            // A same-named cluster in another account must not see the default account's resources.
            review(name, OTHER_ACCOUNT, false);
            String otherToken = mintToken(OTHER_ACCOUNT, otherCluster);
            given().header("Authorization", otherToken)
                    .get("/v1/credentials")
                    .then().statusCode(404);

            createPodIdentityResources(OTHER_ACCOUNT, otherCluster, roleName);
            review(name, DEFAULT_ACCOUNT, true);
            review(name, OTHER_ACCOUNT, true);
            assertCredentialsBelongTo(DEFAULT_ACCOUNT, defaultCluster);
            assertCredentialsBelongTo(OTHER_ACCOUNT, otherCluster);
        } finally {
            cleanUp(DEFAULT_ACCOUNT, name, roleName);
            cleanUp(OTHER_ACCOUNT, name, roleName);
        }
    }

    private Cluster createCluster(String account, String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setAccountId(account);
        cluster.setArn("arn:aws:eks:us-east-1:" + account + ":cluster/" + name);
        cluster.setCreatedAt(Instant.now());
        cluster.setVersion("1.30");
        cluster.setStatus(ClusterStatus.ACTIVE);
        String issuer = oidcService.newIssuerUrl("us-east-1");
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        oidcService.ensureKeyForAccount(account, name, issuer);
        eksService.putClusterForAccount(account, cluster);
        // Persisted clusters lose this @JsonIgnore field; the ARN still names the owner.
        cluster.setAccountId(null);
        return cluster;
    }

    private void createPodIdentityResources(String account, Cluster cluster, String roleName) {
        String roleArn = RequestScopes.callAs(account,
                () -> iamService.createRole(roleName, "/", "{}", null, 3600, null).getArn());
        createPodIdentityResourcesForRole(account, cluster, roleArn);
    }

    private void createPodIdentityResourcesForRole(String account, Cluster cluster, String roleArn) {
        RequestScopes.runAs(account, () -> {
            addonService.create(cluster, new CreateAddonRequest("eks-pod-identity-agent",
                    null, null, null, null, null, null, null));
            associationService.create(cluster, new CreatePodIdentityAssociationRequest(
                    cluster.getName(), NAMESPACE, SERVICE_ACCOUNT, roleArn, null, null, null, null, null));
        });
    }

    private String mintToken(String account, Cluster cluster) {
        return RequestScopes.callAs(account, () -> oidcService.mintServiceAccountToken(
                cluster.getName(), cluster.getIdentity().getOidc().getIssuer(),
                NAMESPACE, SERVICE_ACCOUNT, "pods.eks.amazonaws.com", 3600));
    }

    private void review(String name, String account, boolean patched) {
        Map<String, Object> pod = Map.of("kind", "Pod", "spec", Map.of(
                "serviceAccountName", SERVICE_ACCOUNT, "containers", List.of(Map.of("name", "app"))));
        Map<String, Object> admissionReview = Map.of("apiVersion", "admission.k8s.io/v1",
                "kind", "AdmissionReview", "request", Map.of(
                        "uid", "request-1", "namespace", NAMESPACE, "object", pod));
        given().contentType("application/json").body(admissionReview)
                .post("/_floci/eks/clusters/" + name + "/pod-identity-webhook/scope/" + account)
                .then().statusCode(200)
                .body("response.allowed", equalTo(true))
                .body("response.patch", patched ? notNullValue() : nullValue());
    }

    private void assertCredentialsBelongTo(String account, Cluster cluster) {
        String accessKeyId = given().header("Authorization", mintToken(account, cluster))
                .get("/v1/credentials")
                .then().statusCode(200)
                .body("AccountId", equalTo(account))
                .extract().path("AccessKeyId");
        IamService.EksSessionIdentity identity = iamService.findEksSessionIdentity(accessKeyId).orElseThrow();
        assertEquals(account, identity.accountId());
        assertEquals("arn:aws:iam::" + account + ":role/role-" + cluster.getName(), identity.roleArn());
        iamService.unregisterSession(account, accessKeyId);
        assertTrue(iamService.findEksSessionIdentity(accessKeyId).isEmpty());
    }

    private void cleanUp(String account, String name, String roleName) {
        eksService.findAuthenticationCluster(account, name).ifPresent(cluster ->
                RequestScopes.runAs(account, () -> {
                    associationService.deleteClusterAssociations(cluster);
                    addonService.deleteClusterAddons(cluster);
                    oidcService.deleteKey(name);
                    iamService.findRole(account, roleName).ifPresent(role -> iamService.deleteRole(roleName));
                }));
        eksService.deleteClusterForAccount(account, name);
    }
}
