package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Clusters are regional: a request signed for another region sees none of a cluster's operations
 * work on it, as for an unknown id, and cannot terminate it.
 */
@QuarkusTest
class EmrClusterRegionIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "ElasticMapReduce.";
    private static final String HOME = "eu-west-1";
    private static final String OTHER = "us-west-2";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String region, String action, String body) {
        return given().contentType(CT)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region
                        + "/elasticmapreduce/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    @Test
    void clusterOperationsStayInTheClustersRegion() {
        String clusterId = call(HOME, "RunJobFlow",
                "{\"Name\":\"region-emr\",\"ReleaseLabel\":\"emr-7.5.0\","
                        + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                        + "\"InstanceGroups\":[{\"Name\":\"master\",\"InstanceRole\":\"MASTER\","
                        + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
            .then().statusCode(200)
            .extract().path("JobFlowId");
        String byId = "{\"ClusterId\":\"" + clusterId + "\"}";

        call(OTHER, "DescribeCluster", byId)
            .then().statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        call(OTHER, "AddJobFlowSteps", "{\"JobFlowId\":\"" + clusterId + "\",\"Steps\":[{\"Name\":\"s\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\",\"Args\":[\"echo\"]}}]}")
            .then().statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        call(OTHER, "ListClusters", "{}")
            .then().statusCode(200)
            .body("Clusters.Id", not(hasItem(clusterId)));
        call(OTHER, "SetTerminationProtection",
                "{\"JobFlowIds\":[\"" + clusterId + "\"],\"TerminationProtected\":true}")
            .then().statusCode(200);
        call(OTHER, "TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}")
            .then().statusCode(200);
        call(HOME, "DescribeCluster", byId)
            .then().statusCode(200)
            .body("Cluster.Status.State", equalTo("WAITING"))
            .body("Cluster.TerminationProtected", equalTo(false));

        call(HOME, "TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}")
            .then().statusCode(200);
    }

    // Catches: an instance's or the master's private DNS name in the wrong region's domain.
    @Test
    void listedInstancesUseTheClustersRegionalPrivateDnsDomain() {
        String clusterId = call(HOME, "RunJobFlow",
                "{\"Name\":\"region-dns\",\"ReleaseLabel\":\"emr-7.5.0\","
                        + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                        + "\"InstanceGroups\":[{\"Name\":\"master\",\"InstanceRole\":\"MASTER\","
                        + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
            .then().statusCode(200)
            .extract().path("JobFlowId");

        call(HOME, "ListInstances", "{\"ClusterId\":\"" + clusterId + "\"}")
            .then().statusCode(200)
            .body("Instances[0].PrivateDnsName", equalTo("ip-10-0-0-1.eu-west-1.compute.internal"))
            .body("Instances[0].PrivateIpAddress", equalTo("10.0.0.1"));
        call(HOME, "DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
            .then().statusCode(200)
            .body("Cluster.MasterPublicDnsName", equalTo("ip-10-0-0-1.eu-west-1.compute.internal"));

        call(HOME, "TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}")
            .then().statusCode(200);
    }
}
