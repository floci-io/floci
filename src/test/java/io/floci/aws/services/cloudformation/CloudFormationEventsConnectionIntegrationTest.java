package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions an {@code AWS::Events::Connection} through a CloudFormation stack and reads it back
 * through the EventBridge API. {@code Ref} is the connection name; {@code Fn::GetAtt} gives
 * {@code Arn}, {@code ArnForPolicy} and {@code SecretArn}. An {@code AWS::Events::ApiDestination}
 * can take the connection's ARN, a description change updates the connection in place, removing
 * a property the service cannot clear is refused, and the stack delete removes the connection.
 */
@QuarkusTest
class CloudFormationEventsConnectionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String EVENTS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/events/aws4_request";
    private final List<String> stacks = new ArrayList<>();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        for (String stack : List.copyOf(stacks)) {
            deleteStack(stack);
        }
    }

    @Test
    void createReadsBackThroughDescribeConnectionAndExposesTheAttributes() throws Exception {
        String name = "orders-" + suffix();
        String body = awaitStatus(createStack(template(connection(name, "orders connection"), false)),
                "CREATE_COMPLETE");

        assertEquals(name, output(body, "ConnectionRef"), "Ref is the connection name");
        JsonPath connection = describeConnection(name).then().statusCode(200).extract().jsonPath();
        assertEquals("API_KEY", connection.getString("AuthorizationType"));
        assertEquals("AUTHORIZED", connection.getString("ConnectionState"));
        assertEquals("orders connection", connection.getString("Description"));
        assertEquals("Authorization", connection.getString("AuthParameters.ApiKeyAuthParameters.ApiKeyName"));
        String arn = connection.getString("ConnectionArn");
        assertEquals(arn, output(body, "ConnectionArn"), "GetAtt Arn is the connection ARN");
        assertEquals(arn.substring(0, arn.lastIndexOf('/')), output(body, "ConnectionArnForPolicy"),
                "GetAtt ArnForPolicy is the ARN without the trailing id");
        assertEquals(connection.getString("SecretArn"), output(body, "ConnectionSecretArn"));
    }

    @Test
    void anApiDestinationTakesTheConnectionArn() throws Exception {
        String name = "orders-" + suffix();
        String destination = "orders-dest-" + suffix();
        String body = awaitStatus(createStack(template(connection(name, null), true, destination)),
                "CREATE_COMPLETE");

        String arn = describeConnection(name).then().statusCode(200).extract().jsonPath()
                .getString("ConnectionArn");
        assertEquals(arn, output(body, "ConnectionArn"));
        assertEquals(arn, events("DescribeApiDestination", Map.of("Name", destination)).then().statusCode(200)
                .extract().jsonPath().getString("ConnectionArn"), "the destination points at the real connection");
    }

    @Test
    void aDescriptionChangeUpdatesTheConnectionInPlace() throws Exception {
        String name = "orders-" + suffix();
        String stack = createStack(template(connection(name, "first"), false));
        String arn = output(awaitStatus(stack, "CREATE_COMPLETE"), "ConnectionArn");

        updateStack(stack, template(connection(name, "second"), false));
        String body = awaitStatus(stack, "UPDATE_COMPLETE");

        assertEquals(arn, output(body, "ConnectionArn"), "an in-place update keeps the connection");
        assertEquals("second", describeConnection(name).then().statusCode(200).extract().jsonPath()
                .getString("Description"));
    }

    @Test
    void removingAPropertyTheServiceCannotClearIsRefused() throws Exception {
        String name = "orders-" + suffix();
        String stack = createStack(template(connection(name, "first"), false));
        awaitStatus(stack, "CREATE_COMPLETE");

        updateStack(stack, template(connection(name, null), false));
        awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        List<Map<String, String>> stackEvents = XmlParser.extractGroups(cfn(stack, "DescribeStackEvents", null)
                .then().statusCode(200).extract().asString(), "member");
        assertTrue(stackEvents.stream().anyMatch(event -> "Connection".equals(event.get("LogicalResourceId"))
                        && "UPDATE_FAILED".equals(event.get("ResourceStatus"))
                        && event.get("ResourceStatusReason") != null
                        && event.get("ResourceStatusReason").contains("Removing Description")),
                "no UPDATE_FAILED event naming the removed property in " + stackEvents);
        assertEquals("first", describeConnection(name).then().statusCode(200).extract().jsonPath()
                .getString("Description"));
    }

    @Test
    void aFailedUpdateRollsTheConnectionBack() throws Exception {
        String name = "orders-" + suffix();
        String stack = createStack(template(connection(name, "first"), false));
        awaitStatus(stack, "CREATE_COMPLETE");

        // The connection is updated first; the destination after it points at a connection that
        // does not exist, so the update fails and the connection has to go back to "first".
        updateStack(stack, withBrokenDestination(template(connection(name, "second"), false)));
        awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals("first", describeConnection(name).then().statusCode(200).extract().jsonPath()
                .getString("Description"));
        assertEquals("AUTHORIZED", describeConnection(name).then().statusCode(200).extract().jsonPath()
                .getString("ConnectionState"));
    }

    @Test
    void deleteStackRemovesTheConnection() throws Exception {
        String name = "orders-" + suffix();
        String stack = createStack(template(connection(name, null), false));
        awaitStatus(stack, "CREATE_COMPLETE");
        describeConnection(name).then().statusCode(200);

        deleteStack(stack);

        assertEquals("ResourceNotFoundException", describeConnection(name).then().statusCode(400)
                .extract().jsonPath().getString("__type"));
    }

    /** The template plus an API destination whose connection ARN does not exist, which fails the update. */
    private static String withBrokenDestination(String template) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(template);
        ((ObjectNode) root.get("Resources")).set("Broken", MAPPER.valueToTree(Map.of(
                "Type", "AWS::Events::ApiDestination",
                "Properties", Map.of(
                        "Name", "broken-" + suffix(),
                        "ConnectionArn", "arn:aws:events:us-east-1:000000000000:connection/missing/00000000",
                        "HttpMethod", "POST",
                        "InvocationEndpoint", "https://example.com/events"))));
        return root.toString();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private String createStack(String template) {
        String stack = "cfn-connection-" + suffix();
        cfn(stack, "CreateStack", template).then().statusCode(200);
        stacks.add(stack);
        return stack;
    }

    private static void updateStack(String stack, String template) {
        cfn(stack, "UpdateStack", template).then().statusCode(200);
    }

    private void deleteStack(String stack) {
        cfn(stack, "DeleteStack", null).then().statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Response response = cfn(stack, "DescribeStacks", null);
            assertEquals(400, response.statusCode(), response.asString());
            assertTrue(response.asString().contains("does not exist"), response.asString());
        });
        stacks.remove(stack);
    }

    private static String awaitStatus(String stack, String status) {
        return await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn(stack, "DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> status.equals(XmlParser.extractFirst(body, "StackStatus", null)));
    }

    private static String output(String body, String key) {
        String value = XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue").get(key);
        assertFalse(value == null || value.isBlank(), body);
        return value;
    }

    private static Response cfn(String stack, String action, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.post("/");
    }

    private static Response events(String action, Map<String, ?> body) throws Exception {
        return given().contentType("application/x-amz-json-1.1").header("Authorization", EVENTS_AUTH)
                .header("X-Amz-Target", "AWSEvents." + action).body(MAPPER.writeValueAsString(body)).post("/");
    }

    private static Response describeConnection(String name) throws Exception {
        return events("DescribeConnection", Map.of("Name", name));
    }

    /** An API key connection whose key value is built with {@code Fn::Sub}, as event-streaming templates do. */
    private static Map<String, Object> connection(String name, String description) {
        Map<String, Object> apiKey = new LinkedHashMap<>();
        apiKey.put("ApiKeyName", "Authorization");
        apiKey.put("ApiKeyValue", Map.of("Fn::Sub", "Bearer ${Token}"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("Name", name);
        properties.put("AuthorizationType", "API_KEY");
        properties.put("AuthParameters", Map.of("ApiKeyAuthParameters", apiKey));
        if (description != null) {
            properties.put("Description", description);
        }
        return properties;
    }

    private static String template(Map<String, Object> connectionProperties, boolean withDestination)
            throws Exception {
        return template(connectionProperties, withDestination, null);
    }

    private static String template(Map<String, Object> connectionProperties, boolean withDestination,
                                   String destinationName) throws Exception {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Connection", Map.of("Type", "AWS::Events::Connection", "Properties", connectionProperties));
        if (withDestination) {
            resources.put("Destination", Map.of("Type", "AWS::Events::ApiDestination", "Properties", Map.of(
                    "Name", destinationName,
                    "ConnectionArn", Map.of("Fn::GetAtt", List.of("Connection", "Arn")),
                    "HttpMethod", "POST",
                    "InvocationEndpoint", "https://example.com/events")));
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("ConnectionRef", Map.of("Value", Map.of("Ref", "Connection")));
        outputs.put("ConnectionArn", Map.of("Value", Map.of("Fn::GetAtt", List.of("Connection", "Arn"))));
        outputs.put("ConnectionArnForPolicy",
                Map.of("Value", Map.of("Fn::GetAtt", List.of("Connection", "ArnForPolicy"))));
        outputs.put("ConnectionSecretArn",
                Map.of("Value", Map.of("Fn::GetAtt", List.of("Connection", "SecretArn"))));
        return MAPPER.writeValueAsString(Map.of(
                "Parameters", Map.of("Token", Map.of("Type", "String", "Default", "tok-123")),
                "Resources", resources,
                "Outputs", outputs));
    }
}
