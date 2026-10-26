package io.github.hectorvent.floci.services.organizations;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.EffectivePolicy;
import software.amazon.awssdk.services.organizations.model.OrganizationsException;
import software.amazon.awssdk.services.organizations.model.Policy;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OrganizationsPolicyTypesIntegrationTest {

    @ParameterizedTest
    @CsvSource({"INSPECTOR_POLICY,741623700001", "UPGRADE_ROLLOUT_POLICY,741623700002",
            "BEDROCK_POLICY,741623700003", "S3_POLICY,741623700004",
            "NETWORK_SECURITY_DIRECTOR_POLICY,741623700005", "GUARDDUTY_POLICY,741623700006"})
    void sdkSupportsTheManagementPolicyLifecycle(String type, String account) {
        try (OrganizationsClient client = client(account)) {
            client.createOrganization(request -> request.featureSet("ALL"));
            try {
                String root = client.listRoots().roots().getFirst().id();
                client.enablePolicyType(request -> request.rootId(root).policyType(type));
                assertTrue(client.listRoots().roots().getFirst().policyTypes().stream()
                        .anyMatch(entry -> type.equals(entry.typeAsString()) && "ENABLED".equals(entry.statusAsString())));
                Policy policy = client.createPolicy(request -> request.name("Policy").description("description")
                        .type(type).content("{}")).policy();
                try {
                    assertEquals(type, policy.policySummary().typeAsString());
                    assertEquals(List.of(policy.policySummary()), client.listPolicies(request -> request.filter(type)).policies());
                    String id = policy.policySummary().id();
                    client.attachPolicy(request -> request.policyId(id).targetId(root));
                    try {
                        assertEquals(List.of(policy.policySummary()), client.listPoliciesForTarget(request -> request
                                .targetId(root).filter(type)).policies());
                        EffectivePolicy effective = client.describeEffectivePolicy(request -> request
                                .policyType(type).targetId(account)).effectivePolicy();
                        assertEquals(type, effective.policyTypeAsString());
                        assertEquals(account, effective.targetId());
                        assertEquals("{}", effective.policyContent());
                        assertTrue(client.listAccountsWithInvalidEffectivePolicy(request -> request.policyType(type))
                                .accounts().isEmpty());
                    } finally {
                        client.detachPolicy(request -> request.policyId(id).targetId(root));
                    }
                    assertTrue(client.listPoliciesForTarget(request -> request.targetId(root).filter(type)).policies().isEmpty());
                } finally {
                    client.deletePolicy(request -> request.policyId(policy.policySummary().id()));
                }
                assertTrue(client.listPolicies(request -> request.filter(type)).policies().isEmpty());
                client.disablePolicyType(request -> request.rootId(root).policyType(type));
                assertTrue(client.listRoots().roots().getFirst().policyTypes().stream()
                        .noneMatch(entry -> type.equals(entry.typeAsString())));
            } finally {
                client.deleteOrganization();
            }
        }
    }

    @Test
    void sdkReportsTheMissingGuardDutyEffectivePolicy() {
        String account = "741623700007";
        try (OrganizationsClient client = client(account)) {
            client.createOrganization(request -> request.featureSet("ALL"));
            try {
                OrganizationsException missing = assertThrows(OrganizationsException.class,
                        () -> client.describeEffectivePolicy(request -> request.policyType("GUARDDUTY_POLICY")));
                assertEquals("EffectivePolicyNotFoundException", missing.awsErrorDetails().errorCode());
                assertEquals(400, missing.statusCode());
            } finally {
                client.deleteOrganization();
            }
        }
    }

    private static OrganizationsClient client(String account) {
        return OrganizationsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
