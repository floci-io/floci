package io.floci.aws.core.common;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;
import software.amazon.awssdk.services.eks.model.CreateClusterRequest;
import software.amazon.awssdk.services.eks.model.EksException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.IamTestPolicies.EMPTY_POLICY_DOCUMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EksClusterConditionsIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String CONDITIONS = """
            {"StringEquals":{"eks:authenticationMode":"API_AND_CONFIG_MAP","aws:RequestTag/owner":"team"},
             "Bool":{"eks:endpointPublicAccess":"true","eks:endpointPrivateAccess":"true",
                     "eks:bootstrapClusterCreatorAdminPermissions":"false"}}""";

    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"authenticationMode", "endpointPublicAccess", "endpointPrivateAccess", "bootstrap"})
    void createRequiresEveryExplicitAttributeAndRetainsRequestTagConditions(String field) {
        try (Fixture f = new Fixture()) {
            f.policy(createGrant());
            CreateClusterRequest request = f.request();
            Cluster created = f.create(request);
            assertEquals("API_AND_CONFIG_MAP", created.accessConfig().authenticationModeAsString());
            assertFalse(created.accessConfig().bootstrapClusterCreatorAdminPermissions());
            assertTrue(created.resourcesVpcConfig().endpointPublicAccess());
            assertTrue(created.resourcesVpcConfig().endpointPrivateAccess());
            assertEquals("team", created.tags().get("owner"));
            denied(() -> f.create(changed(f.request(), field, false)));
            denied(() -> f.create(changed(f.request(), field, true)));
            denied(() -> f.create(f.request().toBuilder().tags(Map.of("owner", "other")).build()));
            // All the positive attributes still require PassRole, not just the EKS grant.
            f.policy(createGrant(), false);
            denied(() -> f.create(f.request()));
        }
    }

    @Test
    void explicitDenySeesFalseAndMissingFieldsRemainAbsent() {
        try (Fixture f = new Fixture()) {
            f.policy("""
                    {"Effect":"Allow","Action":"eks:CreateCluster","Resource":"*"},
                    {"Effect":"Deny","Action":"eks:CreateCluster","Resource":"*",
                     "Condition":{"Bool":{"eks:bootstrapClusterCreatorAdminPermissions":"false"}}}""");
            denied(() -> f.create(f.request()));
            f.create(changed(f.request(), "bootstrap", true));
            f.policy("""
                    {"Effect":"Allow","Action":"eks:CreateCluster","Resource":"*"},
                    {"Effect":"Deny","Action":"eks:CreateCluster","Resource":"*",
                     "Condition":{"Null":{"eks:authenticationMode":"true"}}}""");
            denied(() -> f.create(changed(f.request(), "authenticationMode", true)));
            f.create(f.request());
        }
    }

    private static CreateClusterRequest changed(CreateClusterRequest request, String field, boolean omit) {
        return switch (field) {
            case "authenticationMode" -> request.toBuilder().accessConfig(request.accessConfig().toBuilder()
                    .authenticationMode(omit ? null : "API").build()).build();
            case "bootstrap" -> request.toBuilder().accessConfig(request.accessConfig().toBuilder()
                    .bootstrapClusterCreatorAdminPermissions(omit ? null : true).build()).build();
            case "endpointPublicAccess" -> request.toBuilder().resourcesVpcConfig(request.resourcesVpcConfig().toBuilder()
                    .endpointPublicAccess(omit ? null : false).build()).build();
            case "endpointPrivateAccess" -> request.toBuilder().resourcesVpcConfig(request.resourcesVpcConfig().toBuilder()
                    .endpointPrivateAccess(omit ? null : false).build()).build();
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static String createGrant() {
        return """
                {"Effect":"Allow","Action":"eks:CreateCluster","Resource":"*","Condition":%s}"""
                .formatted(CONDITIONS);
    }

    private static void denied(Runnable call) {
        EksException error = assertThrows(EksException.class, call::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    private final class Fixture implements AutoCloseable {
        final String user = "eks-condition-" + UUID.randomUUID();
        final String role;
        final EksClient admin;
        final EksClient client;
        final List<String> clusters = new ArrayList<>();

        Fixture() {
            UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                    auth(ACCOUNT, "iam"), user);
            role = EnforcementFixtures.createRole(cleanup, endpoint.getPort(), ACCOUNT, "/", user,
                    EMPTY_POLICY_DOCUMENT);
            admin = AwsTestClients.create(EksClient.builder(), ACCOUNT, Region.US_EAST_1, endpoint);
            client = AwsTestClients.create(EksClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(),
                    Region.US_EAST_1, endpoint);
        }

        void policy(String statements) {
            policy(statements, true);
        }

        void policy(String statements, boolean passRole) {
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "conditions",
                    """
                    {"Version":"2012-10-17","Statement":[%s,
                     {"Effect":"Allow","Action":"eks:TagResource","Resource":"*"}%s]}"""
                    .formatted(statements, passRole ? ",{\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\","
                            + "\"Resource\":\"" + role + "\"}" : ""));
        }

        CreateClusterRequest request() {
            return CreateClusterRequest.builder().name("condition-" + UUID.randomUUID()).roleArn(role)
                    .resourcesVpcConfig(v -> v.endpointPublicAccess(true).endpointPrivateAccess(true))
                    .accessConfig(a -> a.authenticationMode("API_AND_CONFIG_MAP").bootstrapClusterCreatorAdminPermissions(false))
                    .tags(Map.of("owner", "team")).build();
        }

        Cluster create(CreateClusterRequest request) {
            Cluster cluster = client.createCluster(request).cluster();
            clusters.add(cluster.name());
            return cluster;
        }

        @Override
        public void close() {
            try {
                for (String name : clusters) {
                    admin.deleteCluster(r -> r.name(name));
                }
            } finally {
                client.close();
                admin.close();
            }
        }
    }
}
