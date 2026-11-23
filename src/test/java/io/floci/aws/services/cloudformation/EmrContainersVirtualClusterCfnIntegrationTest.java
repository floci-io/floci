package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import static io.floci.aws.testing.CfnStackWaits.output;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Provisions {@code AWS::EMRContainers::VirtualCluster} through a stack and reads the virtual cluster back through
 * EMR on EKS: {@code Ref} and {@code Fn::GetAtt} name it, a tag change updates it in place, a later resource's failure
 * puts the tag back, a new namespace replaces it, and deleting the stack terminates it.
 */
@QuarkusTest
class EmrContainersVirtualClusterCfnIntegrationTest {

    private static final String EMR_CONTAINERS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/emr-containers/aws4_request";

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("emr-containers-vc-cfn-it");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createRetagRollBackReplaceAndDeleteAVirtualCluster() {
        Stack created = stack.create(template("spark", "v1", false));
        String id = output(created, "Ref");
        assertEquals(id, output(created, "Id"));
        assertEquals("arn:aws:emr-containers:us-east-1:000000000000:/virtualclusters/" + id, output(created, "Arn"));
        describe(id).statusCode(200)
            .body("virtualCluster.name", equalTo("jobs"))
            .body("virtualCluster.state", equalTo("RUNNING"))
            .body("virtualCluster.containerProvider.id", equalTo("eks-cfn"))
            .body("virtualCluster.containerProvider.info.eksInfo.namespace", equalTo("spark"))
            .body("virtualCluster.tags", aMapWithSize(1))
            .body("virtualCluster.tags.stage", equalTo("v1"));

        Stack retagged = stack.update(template("spark", "v2", false), "UPDATE_COMPLETE");
        assertEquals(id, output(retagged, "Ref"));
        describe(id).statusCode(200).body("virtualCluster.tags.stage", equalTo("v2"));

        stack.update(template("spark", "v3", true), "UPDATE_ROLLBACK_COMPLETE");
        describe(id).statusCode(200).body("virtualCluster.tags.stage", equalTo("v2"));

        // ContainerProvider is create-only: a new namespace replaces the virtual cluster, and the committed update
        // terminates the old one.
        Stack replaced = stack.update(template("etl", "v2", false), "UPDATE_COMPLETE");
        String replacement = output(replaced, "Ref");
        assertNotEquals(id, replacement);
        describe(replacement).statusCode(200)
            .body("virtualCluster.containerProvider.info.eksInfo.namespace", equalTo("etl"));
        describe(id).statusCode(200).body("virtualCluster.state", equalTo("TERMINATED"));

        CfnStackWaits.deleteStack(stack.name());

        describe(replacement).statusCode(200).body("virtualCluster.state", equalTo("TERMINATED"));
    }

    private static String template(String namespace, String stage, boolean laterFails) {
        return """
            {
              "Resources": {
                "Cluster": {
                  "Type": "AWS::EMRContainers::VirtualCluster",
                  "Properties": {
                    "Name": "jobs",
                    "ContainerProvider": {"Type": "EKS", "Id": "eks-cfn", "Info": {"EksInfo": {"Namespace": "%s"}}},
                    "Tags": [{"Key": "stage", "Value": "%s"}]
                  }
                }%s
              },
              "Outputs": {
                "Ref": {"Value": {"Ref": "Cluster"}},
                "Id": {"Value": {"Fn::GetAtt": ["Cluster", "Id"]}},
                "Arn": {"Value": {"Fn::GetAtt": ["Cluster", "Arn"]}}
              }
            }
            """.formatted(namespace, stage, laterFails ? CfnStackWaits.failingResourceAfter("Cluster") : "");
    }

    private static ValidatableResponse describe(String id) {
        return given()
            .header("Authorization", EMR_CONTAINERS_AUTH)
            .contentType("application/json")
        .when().get("/virtualclusters/" + id).then();
    }
}
