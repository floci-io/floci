package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code PutResourcePolicy}, {@code GetResourcePolicy} and {@code DeleteResourcePolicy} on a Redshift
 * Serverless namespace, the target {@code aws_redshift_resource_policy} authorizes zero-ETL
 * integrations into.
 */
@QuarkusTest
class RedshiftResourcePolicyIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"redshift.amazonaws.com\"},"
            + "\"Action\":\"redshift:AuthorizeInboundIntegration\",\"Resource\":\"*\"}]}";

    @Inject
    RedshiftServerlessService serverless;

    private String namespaceName;
    private String namespaceArn;

    @BeforeEach
    void createNamespace() {
        namespaceName = "policy-" + UUID.randomUUID().toString().substring(0, 8);
        namespaceArn = serverless.createNamespace(namespaceName, "admin", "dev", null, null, List.of(), List.of(),
                Map.of(), REGION).getNamespaceArn();
    }

    @AfterEach
    void deleteNamespace() {
        if (namespaceName != null) {
            try {
                serverless.deleteNamespace(namespaceName, REGION);
            } catch (RuntimeException alreadyDeleted) {
                // The test deleted it itself.
            }
        }
    }

    @Test
    void aNamespacePolicyIsPutReadReplacedAndDeleted() {
        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy", POLICY)
                .statusCode(200)
                .body("PutResourcePolicyResponse.PutResourcePolicyResult.ResourcePolicy.ResourceArn",
                        equalTo(namespaceArn))
                .body("PutResourcePolicyResponse.PutResourcePolicyResult.ResourcePolicy.Policy",
                        equalTo(POLICY));
        redshift("GetResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(200)
                .body("GetResourcePolicyResponse.GetResourcePolicyResult.ResourcePolicy.ResourceArn",
                        equalTo(namespaceArn))
                .body("GetResourcePolicyResponse.GetResourcePolicyResult.ResourcePolicy.Policy",
                        equalTo(POLICY));

        String replaced = POLICY.replace("AuthorizeInboundIntegration", "CreateInboundIntegration");
        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy", replaced).statusCode(200);
        redshift("GetResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(200)
                .body("GetResourcePolicyResponse.GetResourcePolicyResult.ResourcePolicy.Policy",
                        equalTo(replaced));

        redshift("DeleteResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(200)
                .body(containsString("<DeleteResourcePolicyResponse"));
        redshift("GetResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(404)
                .body(containsString("<Code>ResourceNotFoundFault</Code>"));
        redshift("DeleteResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(404)
                .body(containsString("<Code>ResourceNotFoundFault</Code>"));
    }

    @Test
    void deletingTheNamespaceDeletesItsPolicy() {
        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy", POLICY).statusCode(200);

        serverless.deleteNamespace(namespaceName, REGION);

        redshift("GetResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(404)
                .body(containsString("<Code>ResourceNotFoundFault</Code>"));
    }

    @Test
    void invalidPoliciesUnknownResourcesAndOtherResourceTypesAreRefused() {
        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy", "not json")
                .statusCode(400)
                .body(containsString("<Code>InvalidPolicyFault</Code>"));
        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy", "[]")
                .statusCode(400)
                .body(containsString("<Code>InvalidPolicyFault</Code>"));

        String unknownNamespace = namespaceArn.substring(0, namespaceArn.lastIndexOf('/') + 1)
                + "00000000-0000-0000-0000-000000000000";
        redshift("PutResourcePolicy", "ResourceArn", unknownNamespace, "Policy", POLICY)
                .statusCode(404)
                .body(containsString("<Code>ResourceNotFoundFault</Code>"));
        // The same namespace id in another Region is not the caller's namespace.
        redshift("GetResourcePolicy", "ResourceArn", namespaceArn.replace(REGION, "eu-west-1"))
                .statusCode(404)
                .body(containsString("<Code>ResourceNotFoundFault</Code>"));
        redshift("PutResourcePolicy", "ResourceArn",
                "arn:aws:redshift:us-east-1:000000000000:cluster:no-such-cluster", "Policy", POLICY)
                .statusCode(404)
                .body(containsString("<Code>ResourceNotFoundFault</Code>"));

        redshift("PutResourcePolicy", "ResourceArn",
                "arn:aws:redshift:us-east-1:000000000000:snapshot:warehouse/nightly", "Policy", POLICY)
                .statusCode(400)
                .body(containsString("<Code>UnsupportedOperation</Code>"));
    }

    private static ValidatableResponse redshift(String action, String... params) {
        return QueryRequests.request("test", "redshift", action, params)
                .formParam("Version", "2012-12-01")
                .when().post("/")
                .then();
    }
}
