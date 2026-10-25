package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EcsParameters;
import software.amazon.awssdk.services.eventbridge.model.RuleState;
import software.amazon.awssdk.services.eventbridge.model.Target;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventBridgeEcsTargetSettingsIntegrationTest {

    private static final String ACCOUNT = "741618800001";
    private static final String CLUSTER = "arn:aws:ecs:us-east-1:" + ACCOUNT + ":cluster/test";
    private static final String TASK = "arn:aws:ecs:us-east-1:" + ACCOUNT + ":task-definition/test:1";
    private EventBridgeClient client;
    private String rule;

    @BeforeEach
    void createRule() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        client = EventBridgeClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build();
        rule = "ecs-settings-" + UUID.randomUUID();
        client.putRule(request -> request.name(rule).eventPattern("{}").state(RuleState.DISABLED));
    }

    @AfterEach
    void deleteRule() {
        try {
            client.removeTargets(request -> request.rule(rule).ids("t1", "t2"));
            client.deleteRule(request -> request.name(rule));
        } finally {
            client.close();
        }
    }

    @Test
    void missingEcsSettingsReadBackWithDefaults() {
        putTarget(EcsParameters.builder().taskDefinitionArn(TASK).build());

        EcsParameters ecs = listedTarget().ecsParameters();
        assertEquals(1, ecs.taskCount());
        assertEquals(Boolean.FALSE, ecs.enableECSManagedTags());
        assertEquals(Boolean.FALSE, ecs.enableExecuteCommand());
        assertEquals(TASK, ecs.taskDefinitionArn());
        assertNull(ecs.launchType());
        assertNull(ecs.group());
    }

    @Test
    void explicitEcsSettingsArePreserved() {
        putTarget(EcsParameters.builder().taskDefinitionArn(TASK).taskCount(3)
                .enableECSManagedTags(true).enableExecuteCommand(true).group("workers").build());

        EcsParameters ecs = listedTarget().ecsParameters();
        assertEquals(3, ecs.taskCount());
        assertEquals(Boolean.TRUE, ecs.enableECSManagedTags());
        assertEquals(Boolean.TRUE, ecs.enableExecuteCommand());
        assertEquals("workers", ecs.group());
    }

    @Test
    void replacementUsesDefaultsForNewlyOmittedSettings() {
        putTarget(EcsParameters.builder().taskDefinitionArn(TASK).taskCount(3)
                .enableECSManagedTags(true).enableExecuteCommand(true).build());
        putTarget(EcsParameters.builder().taskDefinitionArn(TASK).build());

        EcsParameters ecs = listedTarget().ecsParameters();
        assertEquals(1, ecs.taskCount());
        assertEquals(Boolean.FALSE, ecs.enableECSManagedTags());
        assertEquals(Boolean.FALSE, ecs.enableExecuteCommand());
    }

    @Test
    void targetsWithoutEcsParametersStayUnchanged() {
        client.putTargets(request -> request.rule(rule).targets(Target.builder().id("t1")
                .arn("arn:aws:sqs:us-east-1:" + ACCOUNT + ":queue").build()));
        assertNull(listedTarget().ecsParameters());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidTaskCountsRejectTheWholeRequest(int count) {
        assertRejected("\"EcsParameters\":{\"TaskDefinitionArn\":\"" + TASK + "\",\"TaskCount\":" + count + "}",
                "1 validation error detected: Value '" + count
                        + "' at 'targets.2.member.ecsParameters.taskCount' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RoleArn", "DeadLetterConfig"})
    void malformedArnsRejectTheWholeRequest(String field) {
        String value = field.equals("RoleArn") ? "\"not-an-arn\"" : "{\"Arn\":\"not-an-arn\"}";
        assertRejected("\"" + field + "\":" + value,
                "Parameter not-an-arn is not valid. Reason: Provided Arn is not in correct format.");
    }

    @ParameterizedTest
    @CsvSource({
            "RoleArn, arn:::::",
            "DeadLetterConfig, arn:::::",
            "RoleArn, arn::iam::741618800001:role/target",
            "DeadLetterConfig, arn::sqs:us-east-1:741618800001:queue",
            "RoleArn, arn:aws:iam:::role/target",
            "DeadLetterConfig, arn:aws:sqs:us-east-1::queue",
            "RoleArn, arn:aws:iam::123:role/target",
            "DeadLetterConfig, arn:aws:sqs:us-east-1:abcdefghijkl:queue",
            "RoleArn, arn:unknown:iam::741618800001:role/target",
            "DeadLetterConfig, arn:unknown:sqs:us-east-1:741618800001:queue",
            "RoleArn, arn:aws:iam::741618800001:",
            "DeadLetterConfig, arn:aws:sqs:us-east-1:741618800001:",
            "RoleArn, arn:aws:iam:us-east-1:741618800001:role/target",
            "DeadLetterConfig, arn:aws:sqs::741618800001:queue",
            "RoleArn, arn:aws:iam::741618800001:user/target",
            "RoleArn, arn:aws:iam::741618800001:role/",
            "RoleArn, arn:aws:sqs:us-east-1:741618800001:queue",
            "DeadLetterConfig, arn:aws:iam::741618800001:role/target"
    })
    void invalidArnComponentsRejectTheWholeRequest(String field, String arn) {
        String value = field.equals("RoleArn") ? "\"" + arn + "\"" : "{\"Arn\":\"" + arn + "\"}";
        assertRejected("\"" + field + "\":" + value,
                "Parameter " + arn + " is not valid. Reason: Provided Arn is not in correct format.");
    }

    @ParameterizedTest
    @CsvSource({"80, false", "80, true", "81, false", "81, true"})
    void deadLetterQueueNamesRespectTheSqsLengthLimit(int length, boolean fifo) {
        String name = "q".repeat(length - (fifo ? 5 : 0)) + (fifo ? ".fifo" : "");
        String arn = "arn:aws:sqs:us-east-1:" + ACCOUNT + ":" + name;
        if (length > 80) {
            assertRejected("\"DeadLetterConfig\":{\"Arn\":\"" + arn + "\"}",
                    "Parameter " + arn + " is not valid. Reason: Provided Arn is not in correct format.");
        } else {
            client.putTargets(request -> request.rule(rule).targets(Target.builder().id("t1").arn(CLUSTER)
                    .deadLetterConfig(config -> config.arn(arn)).build()));
            assertEquals(arn, listedTarget().deadLetterConfig().arn());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws", "aws-us-gov", "aws-cn", "aws-iso", "aws-iso-b", "aws-iso-e", "aws-iso-f", "aws-eusc"})
    void wellFormedRoleAndDeadLetterArnsRemainAccepted(String partition) {
        String role = "arn:" + partition + ":iam::" + ACCOUNT + ":role/service-role/target";
        String queue = "arn:" + partition + ":sqs:us-east-1:" + ACCOUNT + ":dlq";
        client.putTargets(request -> request.rule(rule).targets(Target.builder().id("t1").arn(CLUSTER)
                .roleArn(role).deadLetterConfig(config -> config.arn(queue)).build()));
        assertEquals(role, listedTarget().roleArn());
        assertEquals(queue, listedTarget().deadLetterConfig().arn());
    }

    private void putTarget(EcsParameters ecs) {
        assertEquals(0, client.putTargets(request -> request.rule(rule).targets(Target.builder()
                .id("t1").arn(CLUSTER).ecsParameters(ecs).build())).failedEntryCount());
    }

    private Target listedTarget() {
        return client.listTargetsByRule(request -> request.rule(rule)).targets().getFirst();
    }

    private void assertRejected(String fields, String message) {
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261024/us-east-1/events/aws4_request, SignedHeaders=host, Signature=test")
                .body("""
                        {"Rule":"%s","Targets":[
                          {"Id":"t1","Arn":"%s"},
                          {"Id":"t2","Arn":"%s",%s}
                        ]}
                        """.formatted(rule, CLUSTER, CLUSTER, fields))
                .when().post("/").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(message));
        assertTrue(client.listTargetsByRule(request -> request.rule(rule)).targets().isEmpty());
    }
}
