package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * EMR cluster lifecycle over the AWS JSON 1.1 wire protocol: run job flow → describe →
 * add steps → instance groups → security config → terminate. Covers the
 * ActionOnFailure legacy alias and the InvalidRequestException not-found shape.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmrIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "ElasticMapReduce.";

    private static String clusterId;
    private static String stepId;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    @Test
    @Order(1)
    void runJobFlow() {
        Response resp = call("RunJobFlow",
                "{\"Name\":\"floci-emr\",\"ReleaseLabel\":\"emr-7.5.0\","
                        + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                        + "\"InstanceGroups\":[{\"Name\":\"master\",\"InstanceRole\":\"MASTER\","
                        + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                        + "{\"Name\":\"core\",\"InstanceRole\":\"CORE\","
                        + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2}]},"
                        + "\"Steps\":[{\"Name\":\"initial\",\"ActionOnFailure\":\"TERMINATE_JOB_FLOW\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\",\"Args\":[\"echo\",\"hi\"]}}]}");
        resp.then().statusCode(200)
                .body("JobFlowId", startsWith("j-"))
                .body("ClusterArn", notNullValue());
        clusterId = resp.jsonPath().getString("JobFlowId");
    }

    @Test
    @Order(2)
    void describeClusterIsWaiting() {
        call("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Id", equalTo(clusterId))
                .body("Cluster.Status.State", equalTo("WAITING"))
                .body("Cluster.ReleaseLabel", equalTo("emr-7.5.0"))
                .body("Cluster.InstanceCollectionType", equalTo("INSTANCE_GROUP"))
                .body("Cluster.AutoTerminate", equalTo(false));
    }

    @Test
    @Order(3)
    void listClustersFilteredByState() {
        call("ListClusters", "{\"ClusterStates\":[\"WAITING\"]}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }.Name", equalTo("floci-emr"));
    }

    @Test
    @Order(4)
    void listInstanceGroups() {
        call("ListInstanceGroups", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("InstanceGroups", hasSize(2))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RunningInstanceCount", equalTo(2));
    }

    @Test
    @Order(5)
    void listInstancesSynthetic() {
        call("ListInstances", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Instances", hasSize(3))  // 1 master + 2 core
                .body("Instances[0].Status.State", equalTo("RUNNING"));
    }

    @Test
    @Order(6)
    void addStepWithLegacyActionAlias() {
        stepId = call("AddJobFlowSteps",
                "{\"JobFlowId\":\"" + clusterId + "\",\"Steps\":[{\"Name\":\"added\","
                        + "\"ActionOnFailure\":\"TERMINATE_CLUSTER\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\",\"MainClass\":\"org.Main\","
                        + "\"Args\":[\"run\"]}}]}")
                .then().statusCode(200)
                .body("StepIds", hasSize(1))
                .extract().jsonPath().getString("StepIds[0]");
    }

    @Test
    @Order(7)
    void describeStepCompleted() {
        call("DescribeStep", "{\"ClusterId\":\"" + clusterId + "\",\"StepId\":\"" + stepId + "\"}")
                .then().statusCode(200)
                .body("Step.Id", equalTo(stepId))
                .body("Step.Name", equalTo("added"))
                .body("Step.ActionOnFailure", equalTo("TERMINATE_CLUSTER"))
                .body("Step.Config.MainClass", equalTo("org.Main"))
                .body("Step.Status.State", equalTo("COMPLETED"));
    }

    @Test
    @Order(8)
    void listStepsNewestFirst() {
        call("ListSteps", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Steps", hasSize(2))
                .body("Steps[0].Name", equalTo("added"))  // newest first
                .body("Steps[1].Name", equalTo("initial"));
    }

    @Test
    @Order(9)
    void modifyStepConcurrency() {
        call("ModifyCluster", "{\"ClusterId\":\"" + clusterId + "\",\"StepConcurrencyLevel\":5}")
                .then().statusCode(200)
                .body("StepConcurrencyLevel", equalTo(5));
    }

    @Test
    @Order(10)
    void securityConfigurationRoundTrip() {
        call("CreateSecurityConfiguration",
                "{\"Name\":\"floci-emr-sec\",\"SecurityConfiguration\":\"{\\\"EncryptionConfiguration\\\":{}}\"}")
                .then().statusCode(200)
                .body("Name", equalTo("floci-emr-sec"))
                .body("CreationDateTime", notNullValue());

        call("DescribeSecurityConfiguration", "{\"Name\":\"floci-emr-sec\"}")
                .then().statusCode(200)
                .body("SecurityConfiguration", equalTo("{\"EncryptionConfiguration\":{}}"));

        call("DeleteSecurityConfiguration", "{\"Name\":\"floci-emr-sec\"}").then().statusCode(200);
    }

    @Test
    @Order(11)
    void tagsRoundTrip() {
        call("AddTags", "{\"ResourceId\":\"" + clusterId + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .then().statusCode(200);
        call("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Tags.find { it.Key == 'env' }.Value", equalTo("test"));
        call("RemoveTags", "{\"ResourceId\":\"" + clusterId + "\",\"TagKeys\":[\"env\"]}")
                .then().statusCode(200);
    }

    @Test
    @Order(12)
    void terminateCluster() {
        call("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
        call("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("USER_REQUEST"));
    }

    @Test
    @Order(13)
    void describeUnknownClusterIsInvalidRequest() {
        call("DescribeCluster", "{\"ClusterId\":\"j-DOESNOTEXIST0\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(14)
    void runJobFlowWithoutNameIsValidationException() {
        call("RunJobFlow", "{\"ReleaseLabel\":\"emr-7.5.0\",\"Instances\":{}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "1 validation error detected: Value null at 'name' failed to satisfy constraint: "
                                + "Member must not be null"));
    }

    @Test
    @Order(15)
    void runJobFlowWithEmptyNameIsAccepted() {
        // The Name member is REQUIRED but its model constraint is len=[0..256], so real EMR
        // accepts an explicit empty string; only a missing Name fails framework validation.
        call("RunJobFlow", "{\"Name\":\"\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true}}")
                .then().statusCode(200)
                .body("JobFlowId", startsWith("j-"));
    }

    @Test
    @Order(16)
    void addStepsToUnknownClusterIsInvalidRequest() {
        call("AddJobFlowSteps",
                "{\"JobFlowId\":\"j-DOESNOTEXIST0\",\"Steps\":[{\"Name\":\"s\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Cluster id 'j-DOESNOTEXIST0' is not valid."));
    }

    @Test
    @Order(17)
    void terminationProtectionIsValidationExceptionAndUnprotectedStillTerminate() {
        String protectedId = call("RunJobFlow",
                "{\"Name\":\"protected\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true}}")
                .jsonPath().getString("JobFlowId");
        String unprotectedId = call("RunJobFlow",
                "{\"Name\":\"unprotected\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true}}")
                .jsonPath().getString("JobFlowId");
        call("SetTerminationProtection",
                "{\"JobFlowIds\":[\"" + protectedId + "\"],\"TerminationProtected\":true}")
                .then().statusCode(200);

        // AWS terminates the unprotected clusters in the request and then fails it.
        call("TerminateJobFlows",
                "{\"JobFlowIds\":[\"" + unprotectedId + "\",\"" + protectedId + "\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "Could not shut down one or more job flows since they are termination protected."));

        call("DescribeCluster", "{\"ClusterId\":\"" + unprotectedId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"));
        call("DescribeCluster", "{\"ClusterId\":\"" + protectedId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("WAITING"));
    }
}
