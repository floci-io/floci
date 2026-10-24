package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

/**
 * With the lifecycle durations set, a cluster is seen STARTING and TERMINATING, and its steps PENDING,
 * over the wire. They are an hour long here, so nothing moves on while the test runs.
 */
@QuarkusTest
@TestProfile(EmrTimedLifecycleIntegrationTest.HourLong.class)
class EmrTimedLifecycleIntegrationTest {

    public static class HourLong implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.emr.cluster-startup-delay-seconds", "3600",
                    "floci.services.emr.step-duration-seconds", "3600",
                    "floci.services.emr.cluster-termination-delay-seconds", "3600");
        }
    }

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aStartingClusterKeepsItsStepsPendingAndShutsDownWhenTerminated() {
        String clusterId = emr("RunJobFlow", "{\"Name\":\"hour-long\","
                + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true},"
                + "\"Steps\":[{\"Name\":\"first\",\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}},"
                + "{\"Name\":\"second\",\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200).extract().path("JobFlowId");
        String firstId = emr("ListSteps", "{\"ClusterId\":\"" + clusterId + "\"}").then().statusCode(200)
                .body("Steps.Status.State", equalTo(List.of("PENDING", "PENDING")))
                .extract().path("Steps[1].Id");

        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").then().statusCode(200)
                .body("Cluster.Status.State", equalTo("STARTING"))
                .body("Cluster.Status.Timeline.ReadyDateTime", nullValue());
        emr("ListClusters", "{\"ClusterStates\":[\"STARTING\"]}").then().statusCode(200)
                .body("Clusters.Id", hasItem(clusterId));
        emr("CancelSteps", "{\"ClusterId\":\"" + clusterId + "\",\"StepIds\":[\"" + firstId + "\"]}")
                .then().statusCode(200)
                .body("CancelStepsInfoList[0].Status", equalTo("SUBMITTED"));
        emr("DescribeStep", "{\"ClusterId\":\"" + clusterId + "\",\"StepId\":\"" + firstId + "\"}")
                .then().statusCode(200)
                .body("Step.Status.State", equalTo("CANCELLED"));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);

        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATING"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("USER_REQUEST"));
        emr("ListSteps", "{\"ClusterId\":\"" + clusterId + "\"}").then().statusCode(200)
                .body("Steps.Status.State", equalTo(List.of("CANCELLED", "CANCELLED")));
        emr("AddJobFlowSteps", "{\"JobFlowId\":\"" + clusterId + "\",\"Steps\":[{\"Name\":\"late\","
                + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "A job flow that is shutting down, terminated, or finished may not be modified."));
    }
}
