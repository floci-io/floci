package io.floci.aws.services.emr;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class EmrTransientIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void runJobFlowWithStepsTerminatesDespiteTerminationProtection() {
        String clusterId = emr("RunJobFlow",
                "{\"Name\":\"transient-initial\",\"Instances\":{\"TerminationProtected\":true},"
                        + "\"Steps\":[{\"Name\":\"initial\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.AutoTerminate", equalTo(true))
                .body("Cluster.TerminationProtected", equalTo(true))
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("ALL_STEPS_COMPLETED"))
                .body("Cluster.Status.Timeline.EndDateTime", notNullValue());
    }

    @Test
    void addJobFlowStepsTerminatesTransientCluster() {
        String clusterId = emr("RunJobFlow", "{\"Name\":\"transient-later\",\"Instances\":{}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("WAITING"));

        emr("AddJobFlowSteps",
                "{\"JobFlowId\":\"" + clusterId + "\",\"Steps\":[{\"Name\":\"later\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200);
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("ALL_STEPS_COMPLETED"))
                .body("Cluster.Status.Timeline.EndDateTime", notNullValue());
    }

    @Test
    void keepAliveClusterWaitsAfterItsSteps() {
        String clusterId = emr("RunJobFlow",
                "{\"Name\":\"keep-alive\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true},"
                        + "\"Steps\":[{\"Name\":\"initial\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.AutoTerminate", equalTo(false))
                .body("Cluster.Status.State", equalTo("WAITING"))
                .body("Cluster.Status.Timeline.ReadyDateTime", notNullValue())
                .body("Cluster.Status.Timeline.EndDateTime", nullValue());
    }

    @Test
    void transientClusterWithoutStepsRemainsWaiting() {
        String clusterId = emr("RunJobFlow", "{\"Name\":\"no-steps\",\"Instances\":{}}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.AutoTerminate", equalTo(true))
                .body("Cluster.Status.State", equalTo("WAITING"))
                .body("Cluster.Status.Timeline.EndDateTime", nullValue());
    }

    @Test
    void addingStepsAfterUserTerminationIsRejectedWithoutChangingCluster() {
        String clusterId = emr("RunJobFlow", "{\"Name\":\"manually-ended-transient\",\"Instances\":{}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String clusterRequest = "{\"ClusterId\":\"" + clusterId + "\"}";
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}")
                .then().statusCode(200);
        Object endTime = emr("DescribeCluster", clusterRequest).then().statusCode(200)
                .body("Cluster.Status.StateChangeReason.Code", equalTo("USER_REQUEST"))
                .extract().path("Cluster.Status.Timeline.EndDateTime");

        emr("AddJobFlowSteps",
                "{\"JobFlowId\":\"" + clusterId + "\",\"Steps\":[{\"Name\":\"late\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "A job flow that is shutting down, terminated, or finished may not be modified."));
        emr("ListSteps", clusterRequest).then().statusCode(200).body("Steps", hasSize(0));
        emr("DescribeCluster", clusterRequest).then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("USER_REQUEST"))
                .body("Cluster.Status.Timeline.EndDateTime", equalTo(endTime));
    }

    @Test
    void addingStepsAfterAutomaticTerminationIsRejected() {
        String clusterId = emr("RunJobFlow",
                "{\"Name\":\"auto-ended\",\"Instances\":{},"
                        + "\"Steps\":[{\"Name\":\"initial\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200).extract().path("JobFlowId");
        String clusterRequest = "{\"ClusterId\":\"" + clusterId + "\"}";
        emr("DescribeCluster", clusterRequest).then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("ALL_STEPS_COMPLETED"));

        emr("AddJobFlowSteps",
                "{\"JobFlowId\":\"" + clusterId + "\",\"Steps\":[{\"Name\":\"late\","
                        + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "A job flow that is shutting down, terminated, or finished may not be modified."));
        emr("ListSteps", clusterRequest).then().statusCode(200).body("Steps", hasSize(1));
        emr("DescribeCluster", clusterRequest).then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("ALL_STEPS_COMPLETED"));
    }

    @Test
    void protectedCompletedClusterIsSkippedDuringMixedTermination() {
        String completedId = emr("RunJobFlow",
                "{\"Name\":\"protected-completed\",\"Instances\":{\"TerminationProtected\":true},"
                        + "\"Steps\":[{\"Name\":\"initial\",\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}]}")
                .then().statusCode(200).extract().path("JobFlowId");
        String activeId = emr("RunJobFlow",
                "{\"Name\":\"active\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String completedRequest = "{\"ClusterId\":\"" + completedId + "\"}";
        Object endTime = emr("DescribeCluster", completedRequest).then().statusCode(200)
                .extract().path("Cluster.Status.Timeline.EndDateTime");

        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + completedId + "\",\"" + activeId + "\"]}")
                .then().statusCode(200);
        emr("DescribeCluster", completedRequest).then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("ALL_STEPS_COMPLETED"))
                .body("Cluster.Status.Timeline.EndDateTime", equalTo(endTime));
        emr("DescribeCluster", "{\"ClusterId\":\"" + activeId + "\"}").then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"))
                .body("Cluster.Status.StateChangeReason.Code", equalTo("USER_REQUEST"));
    }
}
