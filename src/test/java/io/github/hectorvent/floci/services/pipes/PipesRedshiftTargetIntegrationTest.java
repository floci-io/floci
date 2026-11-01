package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A pipe from SQS to a Redshift cluster hands each message to the Data API as a BatchExecuteStatement
 * of its {@code Sqls}, with the paths in them read from the message, and the message is consumed.
 * The Data API itself is mocked here; {@code RedshiftDataApiIntegrationTest} covers it against a cluster.
 */
@QuarkusTest
class PipesRedshiftTargetIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/pipe-redshift-source";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/pipe-role";

    @InjectMock
    RedshiftDataService redshiftDataService;

    @Test
    void sqsMessageReachesTheDataApiAsABatch() {
        when(redshiftDataService.batchExecuteStatement(any(), any(), any()))
                .thenReturn(JsonNodeFactory.instance.objectNode());
        given().contentType(SQS_CONTENT_TYPE).formParam("Action", "CreateQueue")
                .formParam("QueueName", "pipe-redshift-source").when().post("/").then().statusCode(200);

        given().contentType("application/json").body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-redshift-source",
                    "Target": "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse",
                    "RoleArn": "%s",
                    "TargetParameters": {"RedshiftDataParameters": {
                        "Database": "dev", "DbUser": "admin", "StatementName": "load",
                        "Sqls": ["$.body.insert", "COMMIT"]}}
                }
                """.formatted(ROLE_ARN))
                .when().post("/v1/pipes/sqs-to-redshift-pipe")
                .then().statusCode(200).body("CurrentState", equalTo("RUNNING"));
        given().contentType(SQS_CONTENT_TYPE).formParam("Action", "SendMessage").formParam("QueueUrl", QUEUE_URL)
                .formParam("MessageBody", "{\"insert\":\"INSERT INTO events VALUES (1)\"}")
                .when().post("/").then().statusCode(200);

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.captor();
        verify(redshiftDataService, timeout(10_000)).batchExecuteStatement(request.capture(), eq("us-east-1"),
                eq(ROLE_ARN));
        assertEquals("warehouse", request.getValue().path("ClusterIdentifier").asText());
        assertEquals("dev", request.getValue().path("Database").asText());
        assertEquals("load", request.getValue().path("StatementName").asText());
        assertEquals("INSERT INTO events VALUES (1)", request.getValue().path("Sqls").path(0).asText());
        assertEquals("COMMIT", request.getValue().path("Sqls").path(1).asText());

        given().when().delete("/v1/pipes/sqs-to-redshift-pipe").then().statusCode(200);
    }

    @Test
    void aPipeWithoutSqlsIsRejectedWhenItIsCreated() {
        given().contentType("application/json").body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-redshift-source",
                    "Target": "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse",
                    "RoleArn": "%s",
                    "TargetParameters": {"RedshiftDataParameters": {"Database": "dev"}}
                }
                """.formatted(ROLE_ARN))
                .when().post("/v1/pipes/no-sqls-redshift-pipe")
                .then().statusCode(400);
    }
}
