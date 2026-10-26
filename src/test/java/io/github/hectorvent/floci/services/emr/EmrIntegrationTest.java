package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Instant;

import static io.github.hectorvent.floci.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EMR cluster lifecycle over the AWS JSON 1.1 wire protocol: run job flow → describe →
 * add steps → instance groups → security config → terminate. Covers the
 * ActionOnFailure legacy alias and the InvalidRequestException not-found shape.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmrIntegrationTest {

    private static String clusterId;
    private static String stepId;

    @Inject
    EmrService emrService;

    @Inject
    ObjectMapper objectMapper;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void runJobFlow() {
        String roleArn = "arn:aws:iam::000000000000:role/emr-initial-step";
        Response resp = emr("RunJobFlow",
                "{\"Name\":\"floci-emr\",\"ReleaseLabel\":\"emr-7.5.0\","
                        + "\"StepExecutionRoleArn\":\"" + roleArn + "\","
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
        String initialStepId = emr("ListSteps", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("Steps[0].Id");
        emr("DescribeStep", "{\"ClusterId\":\"" + clusterId + "\",\"StepId\":\"" + initialStepId + "\"}")
                .then().statusCode(200)
                .body("Step.ExecutionRoleArn", equalTo(roleArn));
    }

    @Test
    @Order(2)
    void describeClusterIsWaiting() {
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Id", equalTo(clusterId))
                .body("Cluster.Status.State", equalTo("WAITING"))
                .body("Cluster.ReleaseLabel", equalTo("emr-7.5.0"))
                .body("Cluster.InstanceCollectionType", equalTo("INSTANCE_GROUP"))
                .body("Cluster.AutoTerminate", equalTo(false))
                .body("Cluster.MasterPublicDnsName", equalTo("ip-10-0-0-1.ec2.internal"));
    }

    @Test
    @Order(3)
    void listClustersFilteredByStateAndCreationTime() throws Exception {
        emr("ListClusters", "{\"ClusterStates\":[\"WAITING\"]}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }.Name", equalTo("floci-emr"));

        emr("ListClusters", "{\"CreatedAfter\":4102444800}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }", nullValue());
        emr("ListClusters", "{\"CreatedBefore\":946684800}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }", nullValue());
        emr("ListClusters", "{\"ClusterStates\":[\"WAITING\"],\"CreatedAfter\":946684800,"
                        + "\"CreatedBefore\":4102444800}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }.Name", equalTo("floci-emr"));

        Instant createdAt = emrService.describeCluster(clusterId).getCreationDateTime();
        assertEquals(0, createdAt.getNano() % 1_000_000);
        BigDecimal epochSeconds = BigDecimal.valueOf(createdAt.getEpochSecond())
                .add(BigDecimal.valueOf(createdAt.getNano(), 9));
        String shortlyBefore = epochSeconds.subtract(new BigDecimal("0.1")).toPlainString();
        String shortlyAfter = epochSeconds.add(new BigDecimal("0.1")).toPlainString();
        emr("ListClusters", "{\"CreatedAfter\":" + shortlyAfter + "}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }", nullValue());
        emr("ListClusters", "{\"CreatedBefore\":" + shortlyBefore + "}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }", nullValue());
        emr("ListClusters", "{\"CreatedAfter\":" + shortlyBefore + ",\"CreatedBefore\":" + shortlyAfter + "}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }.Name", equalTo("floci-emr"));
        emr("ListClusters", "{\"ClusterStates\":[\"TERMINATED\"],\"CreatedAfter\":" + shortlyBefore + "}")
                .then().statusCode(200)
                .body("Clusters.find { it.Id == '" + clusterId + "' }", nullValue());

        EmrCluster cluster = storedCluster("floci-emr-pinned");
        String pinnedId = cluster.getId();
        Instant originalCreationTime = cluster.getCreationDateTime();
        cluster.setCreationDateTime(Instant.ofEpochMilli(1_761_000_000_000L));
        try {
            emr("ListClusters", "{\"CreatedBefore\":1761000000.001}")
                    .then().statusCode(200)
                    .body("Clusters.find { it.Id == '" + pinnedId + "' }.Name", equalTo("floci-emr-pinned"));
            emr("ListClusters", "{\"CreatedAfter\":1760999999.999}")
                    .then().statusCode(200)
                    .body("Clusters.find { it.Id == '" + pinnedId + "' }.Name", equalTo("floci-emr-pinned"));
            emr("ListClusters", "{\"CreatedBefore\":1761000000.000000001}")
                    .then().statusCode(200)
                    .body("Clusters.find { it.Id == '" + pinnedId + "' }", nullValue());
            emr("ListClusters", "{\"CreatedAfter\":1760999999.999999999}")
                    .then().statusCode(200)
                    .body("Clusters.find { it.Id == '" + pinnedId + "' }", nullValue());
        } finally {
            cluster.setCreationDateTime(originalCreationTime);
        }

        cluster.setCreationDateTime(Instant.ofEpochSecond(1_761_000_000L, 123_456_789));
        try {
            JsonNode clusters = objectMapper.readTree(emr("ListClusters", "{}")
                    .then().statusCode(200).extract().asString()).path("Clusters");
            JsonNode listedTime = null;
            for (JsonNode listed : clusters) {
                if (pinnedId.equals(listed.path("Id").asText())) {
                    listedTime = listed.path("Status").path("Timeline").path("CreationDateTime");
                    break;
                }
            }
            assertNotNull(listedTime);
            assertTrue(listedTime.isNumber());
            assertEquals(0, new BigDecimal("1761000000.123").compareTo(listedTime.decimalValue()));

            JsonNode describedTime = objectMapper.readTree(emr("DescribeCluster",
                    "{\"ClusterId\":\"" + pinnedId + "\"}")
                    .then().statusCode(200).extract().asString())
                    .path("Cluster").path("Status").path("Timeline").path("CreationDateTime");
            assertTrue(describedTime.isNumber());
            assertEquals(0, new BigDecimal("1761000000.123").compareTo(describedTime.decimalValue()));

            emr("ListClusters", "{\"CreatedAfter\":" + listedTime.asText() + "}")
                    .then().statusCode(200)
                    .body("Clusters.find { it.Id == '" + pinnedId + "' }", nullValue());
            emr("ListClusters", "{\"CreatedBefore\":" + listedTime.asText() + "}")
                    .then().statusCode(200)
                    .body("Clusters.find { it.Id == '" + pinnedId + "' }", nullValue());
        } finally {
            cluster.setCreationDateTime(originalCreationTime);
        }

        emr("ListClusters", "{\"CreatedAfter\":\"invalid\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("ListClusters", "{\"CreatedBefore\":123456789012345678901234567890}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(4)
    void listInstanceGroups() {
        emr("ListInstanceGroups", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("InstanceGroups", hasSize(2))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RunningInstanceCount", equalTo(2));
    }

    @Test
    @Order(5)
    void listInstancesSynthetic() {
        emr("ListInstances", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Instances", hasSize(3))  // 1 master + 2 core
                .body("Instances[0].Status.State", equalTo("RUNNING"))
                .body("Instances[0].PrivateDnsName", endsWith(".ec2.internal"));
    }

    @Test
    @Order(6)
    void addStepWithLegacyActionAlias() {
        stepId = emr("AddJobFlowSteps",
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
        emr("DescribeStep", "{\"ClusterId\":\"" + clusterId + "\",\"StepId\":\"" + stepId + "\"}")
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
        emr("ListSteps", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Steps", hasSize(2))
                .body("Steps[0].Name", equalTo("added"))  // newest first
                .body("Steps[1].Name", equalTo("initial"));
    }

    @Test
    @Order(9)
    void addStepsPreservesRequestLevelExecutionRole() {
        String roleArn = "arn:aws:iam::000000000000:role/emr-step";
        Response added = emr("AddJobFlowSteps",
                "{\"JobFlowId\":\"" + clusterId + "\",\"ExecutionRoleArn\":\"" + roleArn + "\","
                        + "\"Steps\":[{\"Name\":\"first-role-step\",\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}},"
                        + "{\"Name\":\"second-role-step\",\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}");
        added.then().statusCode(200).body("StepIds", hasSize(2));
        String firstId = added.jsonPath().getString("StepIds[0]");
        String secondId = added.jsonPath().getString("StepIds[1]");

        emr("DescribeStep", "{\"ClusterId\":\"" + clusterId + "\",\"StepId\":\"" + firstId + "\"}")
                .then().statusCode(200)
                .body("Step.ExecutionRoleArn", equalTo(roleArn));
        emr("DescribeStep", "{\"ClusterId\":\"" + clusterId + "\",\"StepId\":\"" + secondId + "\"}")
                .then().statusCode(200)
                .body("Step.ExecutionRoleArn", equalTo(roleArn));
        emr("ListSteps", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Steps.find { it.Id == '" + firstId + "' }", not(hasKey("ExecutionRoleArn")))
                .body("Steps.find { it.Id == '" + secondId + "' }", not(hasKey("ExecutionRoleArn")));
    }

    @Test
    @Order(10)
    void modifyStepConcurrency() {
        emr("ModifyCluster", "{\"ClusterId\":\"" + clusterId + "\",\"StepConcurrencyLevel\":5}")
                .then().statusCode(200)
                .body("StepConcurrencyLevel", equalTo(5));
    }

    @Test
    @Order(11)
    void securityConfigurationRoundTrip() throws Exception {
        emr("CreateSecurityConfiguration",
                "{\"Name\":\"floci-emr-sec\",\"SecurityConfiguration\":\"{\\\"EncryptionConfiguration\\\":{}}\"}")
                .then().statusCode(200)
                .body("Name", equalTo("floci-emr-sec"))
                .body("CreationDateTime", notNullValue());

        emr("DescribeSecurityConfiguration", "{\"Name\":\"floci-emr-sec\"}")
                .then().statusCode(200)
                .body("SecurityConfiguration", equalTo("{\"EncryptionConfiguration\":{}}"));

        emrService.describeSecurityConfiguration("floci-emr-sec")
                .setCreationDateTime(Instant.ofEpochMilli(1_761_000_000_123L));
        try {
            JsonNode describedTime = objectMapper.readTree(emr("DescribeSecurityConfiguration",
                    "{\"Name\":\"floci-emr-sec\"}")
                    .then().statusCode(200).extract().asString()).path("CreationDateTime");
            assertTrue(describedTime.isNumber());
            assertEquals(0, new BigDecimal("1761000000.123").compareTo(describedTime.decimalValue()));

            JsonNode configurations = objectMapper.readTree(emr("ListSecurityConfigurations", "{}")
                    .then().statusCode(200).extract().asString()).path("SecurityConfigurations");
            JsonNode listedTime = null;
            for (JsonNode configuration : configurations) {
                if ("floci-emr-sec".equals(configuration.path("Name").asText())) {
                    listedTime = configuration.path("CreationDateTime");
                    break;
                }
            }
            assertNotNull(listedTime);
            assertTrue(listedTime.isNumber());
            assertEquals(0, new BigDecimal("1761000000.123").compareTo(listedTime.decimalValue()));
        } finally {
            emr("DeleteSecurityConfiguration", "{\"Name\":\"floci-emr-sec\"}")
                    .then().statusCode(200);
        }
    }

    @Test
    @Order(12)
    void tagsRoundTrip() {
        emr("AddTags", "{\"ResourceId\":\"" + clusterId + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .then().statusCode(200);
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Tags.find { it.Key == 'env' }.Value", equalTo("test"));
        emr("RemoveTags", "{\"ResourceId\":\"" + clusterId + "\",\"TagKeys\":[\"env\"]}")
                .then().statusCode(200);
    }

    @Test
    @Order(13)
    void terminateCluster() {
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("USER_REQUEST"));
    }

    @Test
    @Order(14)
    void describeUnknownClusterIsInvalidRequest() {
        emr("DescribeCluster", "{\"ClusterId\":\"j-DOESNOTEXIST0\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(15)
    void runJobFlowWithoutNameIsValidationException() {
        emr("RunJobFlow", "{\"ReleaseLabel\":\"emr-7.5.0\",\"Instances\":{}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "1 validation error detected: Value null at 'name' failed to satisfy constraint: "
                                + "Member must not be null"));
    }

    @Test
    @Order(16)
    void runJobFlowWithEmptyNameIsAccepted() {
        // The Name member is REQUIRED but its model constraint is len=[0..256], so real EMR
        // accepts an explicit empty string; only a missing Name fails framework validation.
        emr("RunJobFlow", "{\"Name\":\"\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true}}")
                .then().statusCode(200)
                .body("JobFlowId", startsWith("j-"));
    }

    @Test
    @Order(17)
    void addStepsToUnknownClusterIsAValidationException() {
        emr("AddJobFlowSteps",
                "{\"JobFlowId\":\"j-DOESNOTEXIST0\",\"Steps\":[{\"Name\":\"s\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Specified job flow ID does not exist."));
    }

    @Test
    @Order(18)
    void terminationProtectionIsValidationExceptionAndUnprotectedStillTerminate() {
        String protectedId = emr("RunJobFlow",
                "{\"Name\":\"protected\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true}}")
                .jsonPath().getString("JobFlowId");
        String unprotectedId = emr("RunJobFlow",
                "{\"Name\":\"unprotected\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true}}")
                .jsonPath().getString("JobFlowId");
        emr("SetTerminationProtection",
                "{\"JobFlowIds\":[\"" + protectedId + "\"],\"TerminationProtected\":true}")
                .then().statusCode(200);

        // AWS terminates the unprotected clusters in the request and then fails it.
        emr("TerminateJobFlows",
                "{\"JobFlowIds\":[\"" + unprotectedId + "\",\"" + protectedId + "\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "Could not shut down one or more job flows since they are termination protected."));

        emr("DescribeCluster", "{\"ClusterId\":\"" + unprotectedId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"));
        emr("DescribeCluster", "{\"ClusterId\":\"" + protectedId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("WAITING"));
    }

    @Test
    void settersAndCancelStepsRefuseARequestWithoutTheMembersTheyRequire() {
        String id = emr("RunJobFlow", "{\"Name\":\"required\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String ids = "\"JobFlowIds\":[\"" + id + "\"]";

        assertMissing("TerminateJobFlows", "{}", "jobFlowIds");
        assertMissing("SetTerminationProtection", "{\"TerminationProtected\":true}", "jobFlowIds");
        assertMissing("SetTerminationProtection", "{" + ids + "}", "terminationProtected");
        assertMissing("SetVisibleToAllUsers", "{" + ids + "}", "visibleToAllUsers");
        assertMissing("SetKeepJobFlowAliveWhenNoSteps", "{" + ids + "}", "keepJobFlowAliveWhenNoSteps");
        assertMissing("SetUnhealthyNodeReplacement", "{" + ids + "}", "unhealthyNodeReplacement");
        assertMissing("CancelSteps", "{\"ClusterId\":\"" + id + "\"}", "stepIds");
        emr("SetVisibleToAllUsers", "{}").then().statusCode(400)
                .body("message", equalTo("2 validation errors detected: "
                        + "Value null at 'jobFlowIds' failed to satisfy constraint: Member must not be null; "
                        + "Value null at 'visibleToAllUsers' failed to satisfy constraint: Member must not be null"));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[]}").then().statusCode(200);

        emr("DescribeCluster", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Cluster.Status.State", equalTo("WAITING"))
                .body("Cluster.TerminationProtected", equalTo(false))
                .body("Cluster.VisibleToAllUsers", equalTo(true))
                .body("Cluster.AutoTerminate", equalTo(false));
        emr("TerminateJobFlows", "{" + ids + "}").then().statusCode(200);
    }

    private static void assertMissing(String action, String body, String member) {
        emr(action, body).then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value null at '" + member
                        + "' failed to satisfy constraint: Member must not be null"));
    }

    // Catches: a cluster stored before the DNS fix keeps reporting its old us-east-1 master name.
    @Test
    void storedClusterReportsTheRegionalMasterDnsName() {
        EmrCluster stored = storedCluster("stale-dns");
        stored.setMasterPublicDnsName("ip-10-0-0-1.us-east-1.compute.internal");
        String id = stored.getId();

        emr("DescribeCluster", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("Cluster.MasterPublicDnsName", equalTo("ip-10-0-0-1.ec2.internal"));

        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}")
                .then().statusCode(200);
    }

    /**
     * A kept-alive cluster as RunJobFlow stores it: the service's own object, which a test can change,
     * unlike the copy DescribeCluster answers from.
     */
    private EmrCluster storedCluster(String name) {
        EmrCluster cluster = new EmrCluster();
        cluster.setName(name);
        cluster.setKeepJobFlowAliveWhenNoSteps(true);
        return emrService.runJobFlow(cluster, "us-east-1");
    }
}
