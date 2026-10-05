package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisions an {@code AWS::IoT::Authorizer} through a CloudFormation stack and reads it back
 * through the IoT API. {@code Ref} is the authorizer name and {@code Fn::GetAtt Arn} its ARN; status,
 * caching, function and tags update in place and a property removed from the template keeps its
 * value; a {@code SigningDisabled} change replaces the authorizer, and is refused for an explicitly
 * named one; and the stack delete removes it, even while ACTIVE or after it was deleted out of band.
 */
@QuarkusTest
class CloudFormationIotAuthorizerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:000000000000:function:cfn-auth";
    private static final String OTHER_FUNCTION = "arn:aws:lambda:us-east-1:000000000000:function:cfn-auth-2";
    private static final String ARN_PREFIX = "arn:aws:iot:us-east-1:000000000000:authorizer/";
    private final List<String> stacks = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String stack : List.copyOf(stacks)) {
            deleteStack(stack);
        }
    }

    @Test
    void createExposesRefAndArnAndReadsBackThroughDescribeAuthorizer() throws Exception {
        String name = "cfn-auth-" + suffix();
        Map<String, Object> properties = properties(name);
        properties.put("Status", "ACTIVE");
        properties.put("Tags", List.of(Map.of("Key", "team", "Value", "a")));

        String body = awaitStatus(createStack(template(properties)), "CREATE_COMPLETE");

        assertEquals(name, output(body, "AuthRef"), "Ref is the authorizer name");
        assertEquals(ARN_PREFIX + name, output(body, "AuthArn"), "GetAtt Arn is the authorizer ARN");
        JsonPath authorizer = describe(name);
        assertEquals(ARN_PREFIX + name, authorizer.getString("authorizerArn"));
        assertEquals(FUNCTION, authorizer.getString("authorizerFunctionArn"));
        assertEquals("ACTIVE", authorizer.getString("status"));
        assertTrue(authorizer.getBoolean("signingDisabled"));
        assertFalse(authorizer.getBoolean("enableCachingForHttp"));
        assertEquals(Map.of("team", "a"), tags(name));
    }

    @Test
    void inPlaceUpdatesKeepTheAuthorizerAndRemovedPropertiesKeepTheirValues() throws Exception {
        String name = "cfn-auth-" + suffix();
        Map<String, Object> properties = properties(name);
        properties.put("Status", "ACTIVE");
        properties.put("Tags", List.of(Map.of("Key", "team", "Value", "a"), Map.of("Key", "stage", "Value", "x")));
        String stack = createStack(template(properties));
        awaitStatus(stack, "CREATE_COMPLETE");

        properties.put("Status", "INACTIVE");
        properties.put("EnableCachingForHttp", true);
        properties.put("Tags", List.of(Map.of("Key", "team", "Value", "b"), Map.of("Key", "env", "Value", "dev")));
        updateStack(stack, template(properties));
        String body = awaitStatus(stack, "UPDATE_COMPLETE");

        assertEquals(name, output(body, "AuthRef"));
        assertEquals(ARN_PREFIX + name, output(body, "AuthArn"));
        JsonPath updated = describe(name);
        assertEquals("INACTIVE", updated.getString("status"));
        assertTrue(updated.getBoolean("enableCachingForHttp"));
        assertEquals(Map.of("team", "b", "env", "dev"), tags(name));

        properties.remove("Status");
        properties.remove("EnableCachingForHttp");
        properties.put("AuthorizerFunctionArn", OTHER_FUNCTION);
        updateStack(stack, template(properties));
        awaitStatus(stack, "UPDATE_COMPLETE");

        JsonPath kept = describe(name);
        assertEquals(OTHER_FUNCTION, kept.getString("authorizerFunctionArn"));
        assertEquals("INACTIVE", kept.getString("status"), "a removed Status keeps its value");
        assertTrue(kept.getBoolean("enableCachingForHttp"), "a removed EnableCachingForHttp keeps its value");
    }

    @Test
    void turningSigningOnReplacesAGeneratedAuthorizer() throws Exception {
        Map<String, Object> properties = properties(null);
        String stack = createStack(template(properties));
        String original = output(awaitStatus(stack, "CREATE_COMPLETE"), "AuthRef");
        assertTrue(original.matches("Auth_[A-Za-z0-9]{12}"), original);
        assertEquals("INACTIVE", describe(original).getString("status"), "no Status creates it INACTIVE");

        String key = rsaPublicKey();
        properties.put("SigningDisabled", false);
        properties.put("TokenKeyName", "tok");
        properties.put("TokenSigningPublicKeys", Map.of("k1", key));
        updateStack(stack, template(properties));
        String body = awaitStatus(stack, "UPDATE_COMPLETE");

        String replacement = output(body, "AuthRef");
        assertNotEquals(original, replacement);
        assertEquals(ARN_PREFIX + replacement, output(body, "AuthArn"));
        JsonPath authorizer = describe(replacement);
        assertFalse(authorizer.getBoolean("signingDisabled"));
        assertEquals("tok", authorizer.getString("tokenKeyName"));
        assertEquals(key, authorizer.getString("tokenSigningPublicKeys.k1"));
        assertGone(original);
    }

    @Test
    void replacingAnExplicitlyNamedAuthorizerIsRefused() throws Exception {
        String name = "cfn-auth-" + suffix();
        Map<String, Object> properties = properties(name);
        properties.put("Status", "ACTIVE");
        String stack = createStack(template(properties));
        awaitStatus(stack, "CREATE_COMPLETE");

        properties.put("SigningDisabled", false);
        properties.put("TokenKeyName", "tok");
        properties.put("TokenSigningPublicKeys", Map.of("k1", rsaPublicKey()));
        updateStack(stack, template(properties));
        awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertResourceEvent(stack, "UPDATE_FAILED", "CloudFormation cannot update a stack when a custom-named "
                + "resource requires replacing. Rename " + name + " and update the stack again.");
        JsonPath authorizer = describe(name);
        assertTrue(authorizer.getBoolean("signingDisabled"));
        assertEquals("ACTIVE", authorizer.getString("status"));
    }

    @Test
    void missingFunctionArnFailsTheCreate() throws Exception {
        String name = "cfn-auth-" + suffix();
        Map<String, Object> properties = properties(name);
        properties.remove("AuthorizerFunctionArn");

        String stack = createStack(template(properties));
        awaitStatus(stack, "ROLLBACK_COMPLETE");

        assertResourceEvent(stack, "CREATE_FAILED",
                "Model validation failed (#: required key [AuthorizerFunctionArn] not found)");
        assertGone(name);
    }

    @Test
    void deleteStackRemovesAnActiveAuthorizer() throws Exception {
        String name = "cfn-auth-" + suffix();
        Map<String, Object> properties = properties(name);
        properties.put("Status", "ACTIVE");
        String stack = createStack(template(properties));
        awaitStatus(stack, "CREATE_COMPLETE");
        assertEquals("ACTIVE", describe(name).getString("status"));

        deleteStack(stack);

        assertGone(name);
    }

    @Test
    void deleteStackToleratesAnAuthorizerDeletedOutOfBand() throws Exception {
        String name = "cfn-auth-" + suffix();
        Map<String, Object> properties = properties(name);
        properties.put("Status", "ACTIVE");
        String stack = createStack(template(properties));
        awaitStatus(stack, "CREATE_COMPLETE");
        given().contentType("application/json").body("{\"status\": \"INACTIVE\"}")
                .when().put("/authorizer/" + name).then().statusCode(200);
        given().when().delete("/authorizer/" + name).then().statusCode(200);

        deleteStack(stack);

        assertGone(name);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String rsaPublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] der = generator.generateKeyPair().getPublic().getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END PUBLIC KEY-----";
    }

    private String createStack(String template) {
        String stack = "cfn-auth-" + suffix();
        cfn(stack, "CreateStack", template).then().statusCode(200);
        stacks.add(stack);
        return stack;
    }

    private static void updateStack(String stack, String template) {
        cfn(stack, "UpdateStack", template).then().statusCode(200);
    }

    /** Waits for the stack to be gone, which a DELETE_FAILED stack never is. */
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

    private static void assertResourceEvent(String stack, String status, String reason) {
        List<Map<String, String>> events = XmlParser.extractGroups(cfn(stack, "DescribeStackEvents", null)
                .then().statusCode(200).extract().asString(), "member");
        assertTrue(events.stream().anyMatch(event -> "Auth".equals(event.get("LogicalResourceId"))
                        && status.equals(event.get("ResourceStatus"))
                        && reason.equals(event.get("ResourceStatusReason"))),
                "no " + status + " event with reason " + reason + " in " + events);
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

    private static JsonPath describe(String name) {
        return given().when().get("/authorizer/" + name).then().statusCode(200)
                .extract().jsonPath().setRootPath("authorizerDescription");
    }

    private static Map<String, String> tags(String name) {
        List<Map<String, String>> tags = given().queryParam("resourceArn", ARN_PREFIX + name)
                .when().get("/tags").then().statusCode(200).extract().jsonPath().getList("tags");
        Map<String, String> byKey = new LinkedHashMap<>();
        tags.forEach(tag -> byKey.put(tag.get("Key"), tag.get("Value")));
        return byKey;
    }

    private static void assertGone(String name) {
        given().when().get("/authorizer/" + name).then().statusCode(404);
    }

    private static Map<String, Object> properties(String name) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (name != null) {
            properties.put("AuthorizerName", name);
        }
        properties.put("AuthorizerFunctionArn", FUNCTION);
        properties.put("SigningDisabled", true);
        return properties;
    }

    private static String template(Map<String, Object> properties) throws Exception {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("AuthRef", Map.of("Value", Map.of("Ref", "Auth")));
        outputs.put("AuthArn", Map.of("Value", Map.of("Fn::GetAtt", List.of("Auth", "Arn"))));
        return MAPPER.writeValueAsString(Map.of(
                "Resources", Map.of("Auth", Map.of("Type", "AWS::IoT::Authorizer", "Properties", properties)),
                "Outputs", outputs));
    }
}
