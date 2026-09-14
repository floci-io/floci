package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2UserDataAttributeIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @ParameterizedTest
    @ValueSource(strings = {"#!/bin/sh\necho 'héllo <world> & friends'\n", "YQ==\n", ""})
    void describesUserDataAsBase64(String userData) {
        String encoded = Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));
        String id = given().header("Authorization", AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("UserData", encoded).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceAttribute")
                    .formParam("InstanceId", id).formParam("Attribute", "userData")
                    .post("/").then().statusCode(200)
                    .body("DescribeInstanceAttributeResponse.instanceId", equalTo(id))
                    .body("DescribeInstanceAttributeResponse.userData.size()", equalTo(1))
                    .body("DescribeInstanceAttributeResponse.userData.value.text()", equalTo(encoded));
        } finally {
            terminate(id);
        }
    }

    @Test
    void describesAbsentUserDataWithoutInventingAValue() {
        String id = given().header("Authorization", AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceAttribute")
                    .formParam("InstanceId", id).formParam("Attribute", "userData")
                    .post("/").then().statusCode(200)
                    .body("DescribeInstanceAttributeResponse.userData.size()", equalTo(1))
                    .body("DescribeInstanceAttributeResponse.userData.value.size()", equalTo(0));
        } finally {
            terminate(id);
        }
    }

    private void terminate(String id) {
        given().header("Authorization", AUTH).formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", id).post("/").then().statusCode(200);
    }
}
