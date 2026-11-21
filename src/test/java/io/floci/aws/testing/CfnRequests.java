package io.floci.aws.testing;

import io.floci.aws.core.common.XmlParser;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

import static io.restassured.RestAssured.given;

public final class CfnRequests {
    private CfnRequests() {}

    /** A CloudFormation Query request; a null {@code stackName} leaves {@code StackName} out. */
    public static RequestSpecification cfn(String action, String stackName) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        return stackName == null ? request : request.formParam("StackName", stackName);
    }

    /**
     * Sends {@code CreateStack} or {@code UpdateStack} with {@code template}, asserts it was accepted, and
     * waits for {@code expectedStatus}; any other terminal status fails with its reason.
     */
    public static void deploy(String action, String stackName, String template, String authorization,
                              String expectedStatus, String... capabilities) {
        RequestSpecification request = cfn(action, stackName)
                .header("Authorization", authorization)
                .formParam("TemplateBody", template);
        for (int i = 0; i < capabilities.length; i++) {
            request.formParam("Capabilities.member." + (i + 1), capabilities[i]);
        }
        request.when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stackName, expectedStatus, authorization);
    }

    public static String physicalIdUnauthenticated(String stackName, String logicalId) {
        return physicalId(stackName, logicalId, null);
    }

    public static String physicalId(String stackName, String logicalId, String authorization) {
        RequestSpecification request = cfn("DescribeStackResource", stackName)
                .formParam("LogicalResourceId", logicalId);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return request.when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("**.find { it.name() == 'PhysicalResourceId' }");
    }

    /** Returns the parsed DescribeStacks response for {@code stackName}. */
    public static XmlPath describe(String stackName, String authorization) {
        return cfn("DescribeStacks", stackName)
                .header("Authorization", authorization)
                .when().post("/")
                .then().statusCode(200)
                .extract().xmlPath();
    }

    /** Returns the value of the stack output {@code key} in a {@link #describe} response. */
    public static String output(XmlPath stack, String key) {
        return stack.getString("**.findAll { it.OutputKey == '" + key + "' }.OutputValue");
    }

    /** Returns the stack-level tags reported by DescribeStacks. */
    public static Map<String, String> tags(String stackName) {
        String xml = cfn("DescribeStacks", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "Key", "Value");
    }

}
