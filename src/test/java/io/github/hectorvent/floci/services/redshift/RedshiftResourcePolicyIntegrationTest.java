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
    /** A policy as Terraform's {@code jsonencode} sends it: compact, keys sorted. */
    private static final String POLICY = "{\"Statement\":[{\"Action\":\"redshift:AuthorizeInboundIntegration\","
            + "\"Condition\":{\"StringEquals\":{\"aws:SourceArn\":\"arn:aws:dynamodb:us-east-1:000000000000:table/orders\"}},"
            + "\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"redshift.amazonaws.com\"},\"Resource\":\"*\"}],"
            + "\"Version\":\"2012-10-17\"}";
    /** The same policy as AWS returns it: its key order, pretty-printed. */
    private static final String STORED_POLICY = """
            {
              "Version" : "2012-10-17",
              "Statement" : [ {
                "Effect" : "Allow",
                "Principal" : {
                  "Service" : "redshift.amazonaws.com"
                },
                "Action" : "redshift:AuthorizeInboundIntegration",
                "Resource" : "*",
                "Condition" : {
                  "StringEquals" : {
                    "aws:SourceArn" : "arn:aws:dynamodb:us-east-1:000000000000:table/orders"
                  }
                }
              } ]
            }""";

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
            } catch (RuntimeException ignored) {
                // Safe to ignore: the test deleted the namespace itself.
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
                        equalTo(STORED_POLICY));
        redshift("GetResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(200)
                .body("GetResourcePolicyResponse.GetResourcePolicyResult.ResourcePolicy.ResourceArn",
                        equalTo(namespaceArn))
                .body("GetResourcePolicyResponse.GetResourcePolicyResult.ResourcePolicy.Policy",
                        equalTo(STORED_POLICY));

        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy",
                POLICY.replace("AuthorizeInboundIntegration", "CreateInboundIntegration")).statusCode(200);
        redshift("GetResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(200)
                .body("GetResourcePolicyResponse.GetResourcePolicyResult.ResourcePolicy.Policy",
                        equalTo(STORED_POLICY.replace("AuthorizeInboundIntegration", "CreateInboundIntegration")));

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
    void tagsAndUpdatesOnTheNamespaceKeepItsPolicy() {
        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy", POLICY).statusCode(200);

        serverless.tagResource(namespaceArn, Map.of("team", "data"), REGION);
        serverless.untagResource(namespaceArn, List.of("team"), REGION);
        serverless.updateNamespace(namespaceName, null, null, null, null, List.of("userlog"), REGION);

        redshift("GetResourcePolicy", "ResourceArn", namespaceArn)
                .statusCode(200)
                .body("GetResourcePolicyResponse.GetResourcePolicyResult.ResourcePolicy.Policy",
                        equalTo(STORED_POLICY));
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
                .body("ErrorResponse.Error.Code", equalTo("InvalidPolicyFault"));
        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy", "[]")
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidPolicyFault"));

        String namespaces = namespaceArn.substring(0, namespaceArn.lastIndexOf('/') + 1);
        String unknownNamespace = namespaces + "00000000-0000-0000-0000-000000000000";
        // An empty policy is refused before the resource is looked up, as AWS does; a valid policy on
        // the same missing namespace is the control.
        redshift("PutResourcePolicy", "ResourceArn", unknownNamespace, "Policy", "{}")
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidPolicyFault"));
        redshift("PutResourcePolicy", "ResourceArn", namespaceArn, "Policy", " { } ")
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidPolicyFault"));
        refused("PutResourcePolicy", unknownNamespace, 404,
                "ResourceNotFoundFault", "The requested namespace doesn't exist.");
        refused("GetResourcePolicy", unknownNamespace, 404,
                "ResourceNotFoundFault", "The requested namespace doesn't exist.");
        refused("PutResourcePolicy", namespaces + "not-a-uuid", 400,
                "InvalidParameterValue", "The format of the provided identifier is invalid.");
        refused("GetResourcePolicy", namespaceArn.replace(REGION, "eu-west-1"), 400, "InvalidParameterValue",
                "The Region in the ARN doesn't match the current Region. Change your current Region to the same "
                        + "Region in your resource ARN.");
        refused("PutResourcePolicy", "arn:aws:redshift:us-east-1:000000000000:cluster:no-such-cluster", 404,
                "ResourceNotFoundFault", "The resource could not be found.");

        refused("GetResourcePolicy", namespaceArn.replace(":namespace/", ":workgroup/"), 400,
                "InvalidParameterValue",
                "Resource type workgroup isn't supported for the resource policy. Choose another resource type.");
        refused("PutResourcePolicy", "arn:aws:redshift:us-east-1:000000000000:snapshot:warehouse/nightly", 400,
                "InvalidParameterValue",
                "Resource type snapshot isn't supported for the resource policy. Choose another resource type.");
    }

    private static void refused(String action, String resourceArn, int status, String code, String message) {
        redshift(action, "ResourceArn", resourceArn, "Policy", POLICY)
                .statusCode(status)
                .body("ErrorResponse.Error.Code", equalTo(code))
                .body("ErrorResponse.Error.Message", equalTo(message));
    }

    private static ValidatableResponse redshift(String action, String... params) {
        return QueryRequests.request("test", "redshift", action, params)
                .formParam("Version", "2012-12-01")
                .when().post("/")
                .then();
    }
}
