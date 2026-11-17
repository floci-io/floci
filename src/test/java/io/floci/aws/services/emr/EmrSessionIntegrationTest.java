package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A cluster's Spark Connect sessions over the AWS JSON 1.1 wire protocol: StartSession, GetSession, ListSessions,
 * GetSessionEndpoint and TerminateSession, and RunJobFlow's and DescribeCluster's {@code SessionEnabled}.
 */
@QuarkusTest
class EmrSessionIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-session";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aSessionStartsSubmittedIsIdleOnceReadAndIsListedConnectedToAndTerminated() throws Exception {
        String clusterId = cluster(true);
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(200)
                .body("Cluster.SessionEnabled", equalTo(true));

        String sessionId = emr("StartSession", """
                {"ClusterId": "%s", "Name": "notebook", "ExecutionRoleArn": "%s", "SessionIdleTimeoutInMinutes": 30,
                 "ClientRequestToken": "%s",
                 "EngineConfigurations": [{"Classification": "spark-defaults",
                   "Properties": {"spark.executor.memory": "4g"}}],
                 "MonitoringConfiguration": {"S3LoggingConfiguration": {"LogUri": "s3://bucket/sessions/"}},
                 "Tags": [{"Key": "team", "Value": "data"}]}
                """.formatted(clusterId, ROLE, UUID.randomUUID()))
                .then().statusCode(200)
                .body("Id", startsWith("is-"))
                .body("ClusterId", equalTo(clusterId))
                .body("Arn", matchesPattern("arn:aws:elasticmapreduce:us-east-1:000000000000:cluster/" + clusterId
                        + "/session/is-[A-Z0-9]{13}"))
                .body("AccountId", equalTo("000000000000"))
                .body("State", equalTo("SUBMITTED"))
                .extract().path("Id");

        String session = emr("GetSession", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\"" + sessionId
                + "\"}")
                .then().statusCode(200)
                .body("Session.Id", equalTo(sessionId))
                .body("Session.Name", equalTo("notebook"))
                .body("Session.State", equalTo("IDLE"))
                .body("Session.ReleaseLabel", equalTo("emr-spark-8.0.0"))
                .body("Session.ExecutionRoleArn", equalTo(ROLE))
                .body("Session.SessionIdleTimeoutInMinutes", equalTo(30))
                .body("Session.EngineConfigurations[0].Classification", equalTo("spark-defaults"))
                .body("Session.MonitoringConfiguration.S3LoggingConfiguration.LogUri",
                        equalTo("s3://bucket/sessions/"))
                .body("Session.Tags[0].Key", equalTo("team"))
                .body("Session.CreatedAt", notNullValue())
                .body("Session.IdleSince", notNullValue())
                .extract().asString();
        // Read by JSON Pointer: a JSON path's "Properties" reads Groovy's own properties of the map, not the member.
        assertEquals("4g", MAPPER.readTree(session)
                .at("/Session/EngineConfigurations/0/Properties/spark.executor.memory").asText());

        emr("ListSessions", "{\"ClusterId\":\"" + clusterId + "\",\"SessionStates\":[\"IDLE\"]}")
                .then().statusCode(200)
                .body("Sessions", hasSize(1))
                .body("Sessions[0].Id", equalTo(sessionId));

        emr("GetSessionEndpoint", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\"" + sessionId + "\"}")
                .then().statusCode(200)
                .body("Endpoint", endsWith("/_aws/emr/sessions/" + clusterId + "/" + sessionId))
                .body("AuthToken", matchesPattern("[0-9a-f]{64}"))
                .body("AuthTokenExpirationTime", notNullValue());

        emr("TerminateSession", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\"" + sessionId + "\"}")
                .then().statusCode(200)
                .body("ClusterId", equalTo(clusterId))
                .body("SessionId", equalTo(sessionId))
                .body("State", equalTo("TERMINATED"));
        emr("ListSessions", "{\"ClusterId\":\"" + clusterId + "\",\"SessionStates\":[\"IDLE\"]}")
                .then().statusCode(200)
                .body("Sessions", hasSize(0));
        terminate(clusterId);
    }

    @Test
    void aSessionIsRefusedOnAClusterWithoutSessionsOrForAMalformedRequest() {
        String disabled = cluster(false);
        emr("StartSession", "{\"ClusterId\":\"" + disabled + "\",\"Name\":\"n\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("DescribeCluster", "{\"ClusterId\":\"" + disabled + "\"}")
                .then().statusCode(200)
                .body("Cluster.SessionEnabled", equalTo(false));

        String clusterId = cluster(true);
        emr("StartSession", "{\"ClusterId\":\"" + clusterId + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("StartSession", "{\"ClusterId\":\"" + clusterId + "\",\"Name\":\"n\",\"EngineConfigurations\":\"x\"}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emr("StartSession", "{\"ClusterId\":\"" + clusterId + "\",\"Name\":\"n\",\"EngineConfigurations\":"
                        + "[{\"Classification\":\"spark-defaults\",\"Properties\":{\"a\":1}}]}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emr("StartSession", "{\"ClusterId\":\"" + clusterId + "\",\"Name\":\"n\",\"Tags\":{\"Key\":\"k\"}}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emr("StartSession", "{\"ClusterId\":\"" + clusterId + "\",\"Name\":\"n\",\"MonitoringConfiguration\":"
                        + "{\"S3LoggingConfiguration\":\"not-a-structure\"}}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emr("StartSession", "{\"ClusterId\":7,\"Name\":\"n\"}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emr("ListSessions", "{\"ClusterId\":\"" + clusterId + "\",\"SessionStates\":\"IDLE\"}")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        emr("GetSession", "{\"ClusterId\":\"" + clusterId + "\",\"SessionId\":\"is-NONE\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        terminate(disabled);
        terminate(clusterId);
    }

    @Test
    void aRetryThatAsksForTheSameSessionGetsTheFirstAnswer() {
        String clusterId = cluster(true);
        String token = UUID.randomUUID().toString();
        String first = emr("StartSession", "{\"ClusterId\":\"" + clusterId + "\",\"Name\":\"n\",\"Tags\":[],"
                        + "\"ClientRequestToken\":\"" + token + "\"}")
                .then().statusCode(200).extract().path("Id");
        emr("StartSession", "{\"ClientRequestToken\":\"" + token + "\",\"Name\":\"n\",\"ClusterId\":\"" + clusterId
                        + "\",\"SessionIdleTimeoutInMinutes\":60}")
                .then().statusCode(200)
                .body("Id", equalTo(first))
                .body("State", equalTo("SUBMITTED"));
        emr("StartSession", "{\"ClusterId\":\"" + clusterId + "\",\"Name\":\"other\",\"ClientRequestToken\":\""
                        + token + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        terminate(clusterId);
    }

    private static String cluster(boolean sessionEnabled) {
        return emr("RunJobFlow", """
                {"Name": "sessions", "ReleaseLabel": "emr-spark-8.0.0", "Applications": [{"Name": "Spark"}],
                 "SessionEnabled": %s,
                 "Instances": {"KeepJobFlowAliveWhenNoSteps": true,
                   "InstanceGroups": [{"Name": "primary", "InstanceRole": "MASTER", "InstanceType": "m5.xlarge",
                     "InstanceCount": 1}]}}
                """.formatted(sessionEnabled))
                .then().statusCode(200).extract().path("JobFlowId");
    }

    private static void terminate(String clusterId) {
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
    }
}
