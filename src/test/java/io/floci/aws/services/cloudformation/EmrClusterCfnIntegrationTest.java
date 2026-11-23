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
import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Provisions {@code AWS::EMR::Cluster} through a stack and reads the cluster back through EMR: {@code Ref} and
 * {@code Fn::GetAtt} name it once it is waiting, an update resizes the core group, raises the step concurrency and
 * retags it in place, a later resource's failure puts those back, a new release label replaces it, and deleting the
 * stack terminates it.
 */
@QuarkusTest
class EmrClusterCfnIntegrationTest {

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("emr-cluster-cfn-it");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createResizeRollBackReplaceAndDeleteACluster() {
        Stack created = stack.create(template("emr-7.5.0", 2, 1, "v1", false));
        String id = output(created, "Ref");
        assertEquals(id, output(created, "Id"));
        describe(id)
            .body("Cluster.Name", equalTo("etl"))
            .body("Cluster.Status.State", equalTo("WAITING"))
            .body("Cluster.MasterPublicDnsName", equalTo(output(created, "MasterPublicDNS")))
            .body("Cluster.Tags.Value", hasItem("v1"));
        coreCount(id, 2);

        Stack resized = stack.update(template("emr-7.5.0", 3, 2, "v2", false), "UPDATE_COMPLETE");
        assertEquals(id, output(resized, "Ref"));
        describe(id).body("Cluster.StepConcurrencyLevel", equalTo(2)).body("Cluster.Tags.Value", hasItem("v2"));
        coreCount(id, 3);

        stack.update(template("emr-7.5.0", 4, 3, "v3", true), "UPDATE_ROLLBACK_COMPLETE");
        describe(id).body("Cluster.StepConcurrencyLevel", equalTo(2)).body("Cluster.Tags.Value", hasItem("v2"));
        coreCount(id, 3);

        // ReleaseLabel replaces the cluster, and the committed update terminates the old one.
        Stack replaced = stack.update(template("emr-7.6.0", 3, 2, "v2", false), "UPDATE_COMPLETE");
        String replacement = output(replaced, "Ref");
        assertNotEquals(id, replacement);
        describe(replacement).body("Cluster.ReleaseLabel", equalTo("emr-7.6.0"));
        describe(id).body("Cluster.Status.State", equalTo("TERMINATED"));

        CfnStackWaits.deleteStack(stack.name());

        describe(replacement).body("Cluster.Status.State", equalTo("TERMINATED"));
    }

    private static String template(String release, int coreCount, int stepConcurrency, String stage,
                                   boolean laterFails) {
        return """
            {
              "Resources": {
                "Cluster": {
                  "Type": "AWS::EMR::Cluster",
                  "Properties": {
                    "Name": "etl",
                    "ReleaseLabel": "%s",
                    "JobFlowRole": "EMR_EC2_DefaultRole",
                    "ServiceRole": "EMR_DefaultRole",
                    "StepConcurrencyLevel": %d,
                    "Instances": {
                      "KeepJobFlowAliveWhenNoSteps": true,
                      "MasterInstanceGroup": {"InstanceCount": 1, "InstanceType": "m5.xlarge"},
                      "CoreInstanceGroup": {"InstanceCount": %d, "InstanceType": "m5.xlarge"}
                    },
                    "Tags": [{"Key": "stage", "Value": "%s"}]
                  }
                }%s
              },
              "Outputs": {
                "Ref": {"Value": {"Ref": "Cluster"}},
                "Id": {"Value": {"Fn::GetAtt": ["Cluster", "Id"]}},
                "MasterPublicDNS": {"Value": {"Fn::GetAtt": ["Cluster", "MasterPublicDNS"]}}
              }
            }
            """.formatted(release, stepConcurrency, coreCount, stage,
                laterFails ? CfnStackWaits.failingResourceAfter("Cluster") : "");
    }

    private static ValidatableResponse describe(String id) {
        return emr("DescribeCluster", "{\"ClusterId\": \"" + id + "\"}").then().statusCode(200);
    }

    private static void coreCount(String id, int count) {
        emr("ListInstanceGroups", "{\"ClusterId\": \"" + id + "\"}").then().statusCode(200)
            .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RequestedInstanceCount", equalTo(count));
    }
}
