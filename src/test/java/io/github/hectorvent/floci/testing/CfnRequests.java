package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.core.common.XmlParser;
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

    /** Returns the stack-level tags reported by DescribeStacks. */
    public static Map<String, String> tags(String stackName) {
        String xml = cfn("DescribeStacks", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "Key", "Value");
    }

}
