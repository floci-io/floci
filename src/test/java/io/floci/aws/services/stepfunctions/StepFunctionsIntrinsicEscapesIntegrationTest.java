package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class StepFunctionsIntrinsicEscapesIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sdkExecutionDecodesIntrinsicLiterals() throws Exception {
        ObjectNode parameters = MAPPER.createObjectNode();
        parameters.put("greeting.$", "States.Format('Welcome to {} {}\\'s playlist.', $.firstName, $.lastName)");
        parameters.put("braces.$", "States.Format('\\{ {} \\}', $.firstName)");
        parameters.put("array.$", "States.Array('it\\'s', 'C:\\\\')");
        ObjectNode pass = MAPPER.createObjectNode().put("Type", "Pass").put("End", true);
        pass.set("Parameters", parameters);
        ObjectNode states = MAPPER.createObjectNode();
        states.set("Format", pass);
        ObjectNode definition = MAPPER.createObjectNode().put("StartAt", "Format");
        definition.set("States", states);

        try (SfnClient client = SfnClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String arn = client.createStateMachine(request -> request.name("intrinsic-escapes-" + UUID.randomUUID())
                    .roleArn("arn:aws:iam::000000000000:role/test-role")
                    .definition(definition.toString())).stateMachineArn();
            try {
                String execution = client.startExecution(request -> request.stateMachineArn(arn)
                        .input("{\"firstName\":\"Ann\",\"lastName\":\"Lee\"}"))
                        .executionArn();
                await().atMost(Duration.ofSeconds(10)).until(() -> !"RUNNING".equals(client.describeExecution(
                        request -> request.executionArn(execution)).statusAsString()));
                DescribeExecutionResponse result = client.describeExecution(request -> request.executionArn(execution));
                assertEquals("SUCCEEDED", result.statusAsString(), result.toString());
                JsonNode output = MAPPER.readTree(result.output());
                assertEquals("Welcome to Ann Lee's playlist.", output.path("greeting").asText());
                assertEquals("{ Ann }", output.path("braces").asText());
                assertEquals("it's", output.path("array").get(0).asText());
                assertEquals("C:\\", output.path("array").get(1).asText());
            } finally {
                client.deleteStateMachine(request -> request.stateMachineArn(arn));
            }
        }
    }
}
