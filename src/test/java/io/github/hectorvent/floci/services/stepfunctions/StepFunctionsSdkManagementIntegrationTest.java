package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class StepFunctionsSdkManagementIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "771661200001";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "eu-west-2,aws", "cn-north-1,aws-cn"})
    void ecsManagementTasksUseExistingServicesAndSdkResponseCasing(String region, String partition) throws Exception {
        String cluster = "sdk-management-" + UUID.randomUUID();
        String family = cluster + "-task";
        String target = "AmazonEC2ContainerServiceV20141113.";
        call("ecs", target + "CreateCluster", Map.of("clusterName", cluster), region, ACCOUNT);
        String definition = call("ecs", target + "RegisterTaskDefinition", Map.of("family", family,
                "containerDefinitions", List.of(Map.of("name", "app", "image", "example:test", "memory", 128))),
                region, ACCOUNT).path("taskDefinition.taskDefinitionArn");
        try {
            for (String name : List.of("first", "second")) {
                call("ecs", target + "CreateService", Map.of("cluster", cluster, "serviceName", name,
                        "taskDefinition", definition, "desiredCount", 0), region, ACCOUNT);
            }
            JsonNode page = task("ecs", "listServices", Map.of("Cluster", cluster, "MaxResults", 1), region, partition);
            assertEquals(1, page.path("ServiceArns").size());
            assertTrue(page.path("NextToken").isTextual());
            JsonNode next = task("ecs", "listServices", Map.of("Cluster", cluster, "MaxResults", 1,
                    "NextToken", page.path("NextToken").asText()), region, partition);
            assertEquals(1, next.path("ServiceArns").size());
            assertNotEquals(page.path("ServiceArns"), next.path("ServiceArns"));

            JsonNode updated = task("ecs", "updateService", Map.of("Cluster", cluster, "Service", "first",
                    "ForceNewDeployment", true, "DeploymentConfiguration",
                    Map.of("MinimumHealthyPercent", 0, "MaximumPercent", 100)), region, partition);
            assertEquals("first", updated.path("Service").path("ServiceName").asText());
            assertEquals(0, updated.path("Service").path("DeploymentConfiguration").path("MinimumHealthyPercent").asInt(-1));
            assertTrue(updated.path("Service").path("CreatedAt").isTextual());
            JsonNode described = task("ecs", "describeServices", Map.of("Cluster", cluster,
                    "Services", List.of("first", "missing")), region, partition);
            assertEquals("first", described.path("Services").get(0).path("ServiceName").asText());
            assertEquals("MISSING", described.path("Failures").get(0).path("Reason").asText());

            JsonNode otherAccount = runTask("ecs", "listServices", Map.of("Cluster", cluster), region, partition,
                    "771661200002", true);
            assertTrue(otherAccount.path("Error").asText().startsWith("Ecs."));
        } finally {
            for (String name : List.of("first", "second")) {
                call("ecs", target + "DeleteService", Map.of("cluster", cluster, "service", name, "force", true), region, ACCOUNT);
            }
            call("ecs", target + "DeregisterTaskDefinition", Map.of("taskDefinition", definition), region, ACCOUNT);
            call("ecs", target + "DeleteTaskDefinitions", Map.of("taskDefinitions", List.of(definition)), region, ACCOUNT);
            call("ecs", target + "DeleteCluster", Map.of("cluster", cluster), region, ACCOUNT);
        }
    }

    @Test
    void describeSecretPreservesOpaqueVersionIdsAndReturnsCatchableErrors() throws Exception {
        String region = "eu-west-2";
        String name = "sdk-secret-" + UUID.randomUUID();
        String version = "lowercase-" + UUID.randomUUID();
        String arn = call("secretsmanager", "secretsmanager.CreateSecret", Map.of("Name", name,
                "SecretString", "synthetic", "ClientRequestToken", version), region, ACCOUNT).path("ARN");
        try {
            JsonNode described = task("secretsmanager", "describeSecret", Map.of("SecretId", arn), region, "aws");
            assertEquals(arn, described.path("ARN").asText());
            assertEquals("AWSCURRENT", described.path("VersionIdsToStages").path(version).get(0).asText());
            assertTrue(described.path("CreatedDate").isTextual());
            assertFalse(described.has("SecretString"));
            JsonNode error = runTask("secretsmanager", "describeSecret", Map.of("SecretId", name), region, "aws",
                    "771661200002", true);
            assertEquals("SecretsManager.ResourceNotFoundException", error.path("Error").asText());
        } finally {
            call("secretsmanager", "secretsmanager.DeleteSecret", Map.of("SecretId", arn,
                    "ForceDeleteWithoutRecovery", true), region, ACCOUNT);
        }
    }

    @Test
    void lambdaMappingTasksUpdateAnActualMappingAndPreserveItsUuid() throws Exception {
        String region = "eu-west-2";
        String name = "sdk-mapping-" + UUID.randomUUID();
        // An empty ZIP is a valid deployment archive; no invocation is needed for this management fixture.
        Response created = lambda("post", "/2015-03-31/functions", Map.of("FunctionName", name,
                "Runtime", "python3.12", "Role", "arn:aws:iam::" + ACCOUNT + ":role/test-role",
                "Handler", "index.handler", "Code", Map.of("ZipFile", "UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA==")), region);
        String function = created.path("FunctionArn");
        String source = "arn:aws:sqs:" + region + ":" + ACCOUNT + ":" + name;
        String uuid = lambda("post", "/2015-03-31/event-source-mappings", Map.of("FunctionName", function,
                "EventSourceArn", source, "Enabled", false), region).path("UUID");
        try {
            JsonNode listed = task("lambda", "listEventSourceMappings", Map.of("FunctionName", function), region, "aws");
            assertEquals(uuid, listed.path("EventSourceMappings").get(0).path("UUID").asText());
            JsonNode updated = task("lambda", "updateEventSourceMapping", Map.of("UUID", uuid,
                    "Enabled", false, "BatchSize", 3), region, "aws");
            assertEquals(uuid, updated.path("UUID").asText());
            assertEquals(3, updated.path("BatchSize").asInt());
            assertEquals("Disabled", updated.path("State").asText());
            assertTrue(updated.path("LastModified").isTextual());
            assertEquals(3, lambda("get", "/2015-03-31/event-source-mappings/" + uuid, Map.of(), region)
                    .<Integer>path("BatchSize"));
            JsonNode pagination = runTask("lambda", "listEventSourceMappings", Map.of("FunctionName", function,
                    "MaxItems", 1), region, "aws", ACCOUNT, true);
            assertEquals("Lambda.InvalidParameterValueException", pagination.path("Error").asText());
            JsonNode error = runTask("lambda", "updateEventSourceMapping", Map.of("UUID", uuid,
                    "Enabled", true), region, "aws", "771661200002", true);
            assertEquals("Lambda.ResourceNotFoundException", error.path("Error").asText());
        } finally {
            lambda("delete", "/2015-03-31/event-source-mappings/" + uuid, Map.of(), region);
            lambda("delete", "/2015-03-31/functions/" + name, Map.of(), region);
        }
    }

    private static JsonNode task(String service, String action, Map<String, Object> parameters,
                                  String region, String partition) throws Exception {
        return runTask(service, action, parameters, region, partition, ACCOUNT, false);
    }

    private static JsonNode runTask(String service, String action, Map<String, Object> parameters,
                                     String region, String partition, String account, boolean catchError) throws Exception {
        ObjectNode task = MAPPER.createObjectNode().put("Type", "Task").put("End", true)
                .put("Resource", "arn:" + partition + ":states:::aws-sdk:" + service + ":" + action);
        task.set("Parameters", MAPPER.valueToTree(parameters));
        ObjectNode states = MAPPER.createObjectNode();
        states.set("Call", task);
        if (catchError) {
            task.remove("End");
            task.set("Catch", MAPPER.valueToTree(List.of(Map.of("ErrorEquals", List.of("States.ALL"), "Next", "Caught"))));
            task.put("Next", "Caught");
            states.set("Caught", MAPPER.valueToTree(Map.of("Type", "Pass", "End", true)));
        }
        ObjectNode definition = MAPPER.createObjectNode().put("StartAt", "Call");
        definition.set("States", states);
        try (SfnClient client = SfnClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build()) {
            String arn = client.createStateMachine(request -> request.name("sdk-" + UUID.randomUUID())
                    .roleArn("arn:" + partition + ":iam::" + account + ":role/test-role")
                    .definition(definition.toString())).stateMachineArn();
            try {
                String execution = client.startExecution(request -> request.stateMachineArn(arn).input("{}"))
                        .executionArn();
                await().atMost(Duration.ofSeconds(10)).until(() -> !"RUNNING".equals(client.describeExecution(
                        request -> request.executionArn(execution)).statusAsString()));
                DescribeExecutionResponse result = client.describeExecution(request -> request.executionArn(execution));
                assertEquals("SUCCEEDED", result.statusAsString(), result.toString());
                return MAPPER.readTree(result.output());
            } finally {
                client.deleteStateMachine(request -> request.stateMachineArn(arn));
            }
        }
    }

    private static Response call(String service, String target, Map<String, ?> request, String region, String account) {
        return given().contentType("states".equals(service) ? "application/x-amz-json-1.0" : "application/x-amz-json-1.1")
                .header("X-Amz-Target", target)
                .header("Authorization", authorization(service, region, account)).body(request)
                .post("/").then().statusCode(200).extract().response();
    }

    private static Response lambda(String method, String path, Map<String, ?> request, String region) {
        return given().contentType("application/json").header("Authorization", authorization("lambda", region, ACCOUNT))
                .body(request).request(method, path).then().statusCode(lessThan(300))
                .extract().response();
    }

    private static String authorization(String service, String region, String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261030/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=synthetic";
    }
}
