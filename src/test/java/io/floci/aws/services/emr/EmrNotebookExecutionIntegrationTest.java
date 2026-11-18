package io.floci.aws.services.emr;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Notebook executions over the AWS JSON 1.1 wire protocol: StartNotebookExecution, DescribeNotebookExecution,
 * ListNotebookExecutions and StopNotebookExecution, with the step duration of 0 the tests run with, which finishes an
 * execution as soon as it is read.
 */
@QuarkusTest
class EmrNotebookExecutionIntegrationTest {

    @Inject
    EmrService emrService;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anExecutionIsStartedDescribedAndListedOnACluster() {
        String clusterId = cluster();

        String id = emr("StartNotebookExecution", """
                {"EditorId": "e-ABCDEFG123456", "RelativePath": "test.ipynb", "NotebookExecutionName": "my-execution",
                 "NotebookParams": "{\\"input_param\\": \\"my-value\\"}",
                 "ExecutionEngine": {"Id": "%s", "MasterInstanceSecurityGroupId": "sg-05ce12e58cd4f715e"},
                 "ServiceRole": "EMR_Notebooks_DefaultRole", "OutputNotebookFormat": "HTML",
                 "NotebookS3Location": {"Bucket": "notebooks", "Key": "in/test.ipynb"},
                 "EnvironmentVariables": {"KERNEL_LAUNCH_TIMEOUT": "350"},
                 "Tags": [{"Key": "team", "Value": "data"}]}
                """.formatted(clusterId))
                .then().statusCode(200)
                .body("NotebookExecutionId", matchesPattern("ex-[A-Z0-9]{29}"))
                .extract().path("NotebookExecutionId");

        emr("DescribeNotebookExecution", "{\"NotebookExecutionId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("NotebookExecution.NotebookExecutionId", equalTo(id))
                .body("NotebookExecution.EditorId", equalTo("e-ABCDEFG123456"))
                .body("NotebookExecution.ExecutionEngine.Id", equalTo(clusterId))
                .body("NotebookExecution.ExecutionEngine.Type", equalTo("EMR"))
                .body("NotebookExecution.ExecutionEngine.MasterInstanceSecurityGroupId",
                        equalTo("sg-05ce12e58cd4f715e"))
                .body("NotebookExecution.NotebookExecutionName", equalTo("my-execution"))
                .body("NotebookExecution.Status", equalTo("FINISHED"))
                .body("NotebookExecution.LastStateChangeReason",
                        equalTo("Execution is finished for cluster " + clusterId + "."))
                .body("NotebookExecution.Arn", equalTo(
                        "arn:aws:elasticmapreduce:us-east-1:000000000000:notebook-execution/" + id))
                .body("NotebookExecution.StartTime", notNullValue())
                .body("NotebookExecution.EndTime", notNullValue())
                .body("NotebookExecution.NotebookS3Location.Key", equalTo("in/test.ipynb"))
                .body("NotebookExecution.EnvironmentVariables.KERNEL_LAUNCH_TIMEOUT", equalTo("350"))
                .body("NotebookExecution.Tags[0].Key", equalTo("team"));

        // What an IAM condition on aws:ResourceTag reads of the execution.
        assertEquals(Map.of("team", "data"),
                emrService.storedTags("arn:aws:elasticmapreduce:us-east-1:000000000000:notebook-execution/" + id));
        emr("ListNotebookExecutions", "{\"ExecutionEngineId\":\"" + clusterId + "\",\"Status\":\"FINISHED\"}")
                .then().statusCode(200)
                .body("NotebookExecutions.NotebookExecutionId", hasItem(id))
                .body("NotebookExecutions[0].ExecutionEngineId", equalTo(clusterId));
        emr("StopNotebookExecution", "{\"NotebookExecutionId\":\"" + id + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
    }

    @Test
    void aMalformedOrIncompleteRequestIsRefused() {
        String clusterId = cluster();
        emr("StartNotebookExecution", "{\"ExecutionEngine\": \"" + clusterId + "\", \"ServiceRole\": \"r\"}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emr("StartNotebookExecution", "{\"ExecutionEngine\": {\"Id\": \"" + clusterId + "\"}, \"ServiceRole\": \"r\","
                        + " \"EnvironmentVariables\": {\"A\": 1}}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emr("StartNotebookExecution", "{\"ExecutionEngine\": {\"Id\": \"" + clusterId + "\"}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emr("DescribeNotebookExecution", "{\"NotebookExecutionId\": \"ex-NOSUCHEXECUTION\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
    }

    private static String cluster() {
        return emr("RunJobFlow", """
                {"Name": "notebooks", "ReleaseLabel": "emr-6.15.0",
                 "Applications": [{"Name": "Hadoop"}, {"Name": "Spark"}, {"Name": "Livy"},
                   {"Name": "JupyterEnterpriseGateway"}],
                 "Instances": {"KeepJobFlowAliveWhenNoSteps": true,
                   "InstanceGroups": [{"Name": "primary", "InstanceRole": "MASTER", "InstanceType": "m5.xlarge",
                     "InstanceCount": 1}]}}
                """)
                .then().statusCode(200).extract().path("JobFlowId");
    }
}
