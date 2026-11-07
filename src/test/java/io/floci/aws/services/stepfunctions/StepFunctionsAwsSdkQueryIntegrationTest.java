package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.PartitionMatrix;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises the Step Functions {@code aws-sdk:*} / optimized service integrations added for query and
 * REST services: CloudFormation (query protocol bridged to JSON), EC2 DescribeRegions, and S3
 * PutObject. These are the integrations DPS-style provisioning state machines rely on.
 */
@QuarkusTest
class StepFunctionsAwsSdkQueryIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void ec2DescribeRegions_returnsRegionsArray() throws Exception {
        String output = execute("ec2-regions", "arn:aws:states:::aws-sdk:ec2:describeRegions", "{}");
        JsonNode result = mapper.readTree(output);
        assertTrue(result.path("Regions").isArray(), "Regions should be an array");
        assertFalse(result.path("Regions").isEmpty(), "Regions should be non-empty");
        assertFalse(result.path("Regions").get(0).path("RegionName").asText().isBlank());
    }

    @Test
    void ec2DescribeRegions_listsTheRegionsOfTheExecutionsPartition() throws Exception {
        String auth = PartitionMatrix.sigV4Auth("cn-north-1", "states");
        String definition = """
                {"StartAt":"Action","States":{"Action":{"Type":"Task",
                 "Resource":"arn:aws-cn:states:::aws-sdk:ec2:describeRegions","Parameters":{},"End":true}}}
                """;
        String smArn = createStateMachine(auth, "ec2-regions-cn-" + System.currentTimeMillis(), definition,
                "arn:aws-cn:iam::000000000000:role/test-role");
        try {
            JsonNode regions = mapper.readTree(waitForExecution(auth, startExecution(auth, smArn))).path("Regions");
            List<String> names = regions.findValuesAsText("RegionName");
            assertEquals(List.of("cn-north-1", "cn-northwest-1"), names.stream().sorted().toList());
            assertEquals("ec2.cn-north-1.amazonaws.com.cn",
                    regions.get(names.indexOf("cn-north-1")).path("Endpoint").asText());
        } finally {
            signed(auth)
                    .header("X-Amz-Target", "AWSStepFunctions.DeleteStateMachine")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("""
                            {"stateMachineArn": "%s"}
                            """.formatted(smArn))
                    .when().post("/");
        }
    }

    @Test
    void cloudFormation_validateTemplate_dispatchesAndParses() throws Exception {
        String params = """
                {"TemplateBody": "{\\"Resources\\":{\\"Q\\":{\\"Type\\":\\"AWS::SQS::Queue\\"}}}"}
                """;
        // A successful execution proves the query-protocol bridge dispatched to CloudFormation and
        // converted the XML response without error.
        String output = execute("cfn-validate", "arn:aws:states:::aws-sdk:cloudformation:validateTemplate", params);
        assertTrue(mapper.readTree(output).isObject());
    }

    @Test
    void s3PutObject_storesObject() throws Exception {
        String bucket = "sfn-s3-put-" + System.currentTimeMillis();
        given().when().put("/" + bucket).then().statusCode(200);

        String params = """
                {"Bucket": "%s", "Key": "from-sfn.txt", "Body": "hello from step functions"}
                """.formatted(bucket);
        String output = execute("s3-put", "arn:aws:states:::s3:putObject", params);
        assertTrue(mapper.readTree(output).has("ETag"), "PutObject result should carry an ETag");

        // Confirm the object is actually retrievable.
        given().when().get("/" + bucket + "/from-sfn.txt")
                .then().statusCode(200).body(Matchers.containsString("hello from step functions"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String execute(String nameSuffix, String resource, String parameters) throws Exception {
        String definition = """
                {"StartAt":"Action","States":{"Action":{"Type":"Task","Resource":"%s","Parameters":%s,"End":true}}}
                """.formatted(resource, parameters.strip());
        String smArn = createStateMachine(null, nameSuffix + "-" + System.currentTimeMillis(), definition, ROLE_ARN);
        String execArn = startExecution(null, smArn);
        return waitForExecution(null, execArn);
    }

    /** A request signed with {@code auth}, or unsigned (the default region) when it is null. */
    private static RequestSpecification signed(String auth) {
        return auth == null ? given() : given().header("Authorization", auth);
    }

    private String createStateMachine(String auth, String name, String definition, String roleArn) {
        Response resp = signed(auth)
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"name": "%s", "definition": %s, "roleArn": "%s"}
                        """.formatted(name, quote(definition), roleArn))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private String startExecution(String auth, String smArn) {
        Response resp = signed(auth)
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "input": "{}"}
                        """.formatted(smArn))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private String waitForExecution(String auth, String execArn) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Response resp = signed(auth)
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("""
                            {"executionArn": "%s"}
                            """.formatted(execArn))
                    .when().post("/");
            String status = resp.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return resp.jsonPath().getString("output");
            }
            if ("FAILED".equals(status) || "ABORTED".equals(status) || "TIMED_OUT".equals(status)) {
                fail("Execution " + status + ": " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
        return null;
    }

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
